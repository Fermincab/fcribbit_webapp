/*
 * JNI bridge for the Ribbit Android 2.3 port.
 *
 * The modem is the web app's own C++ code (webassembly/dsp/encoder.hh and
 * decoder.hh), compiled unchanged; jni/shim/emscripten.h stubs out EM_ASM.
 * Audio crosses the bridge as 16-bit PCM at 8 kHz, the same rate the web app
 * uses for its AudioContext.
 */
#include <jni.h>
#include <new>
#include <string.h>
#include "encoder.hh"
#include "decoder.hh"
#include "ribbit_crypto.h"

#define FN(name) Java_com_fcribbit_ribbit_Modem_##name

static const int PAYLOAD_BYTES = 256;
static const int CHUNK = 160;          // same feed size as webassembly/ribbit.cc
static const int SALT_BYTES = 8;
static const int IV_BYTES = 12;
static const int TAG_BYTES = 16;
static const int ITERATIONS = 100000;   // must match RibbitCrypto in index.html

static Encoder *encoder;
static Decoder *decoder;
static float rx_chunk[CHUNK];
static int rx_fill;

extern "C" {

JNIEXPORT jboolean JNICALL FN(nativeCreate)(JNIEnv *, jclass)
{
	if (!encoder)
		encoder = new (std::nothrow) Encoder();
	if (!decoder)
		decoder = new (std::nothrow) Decoder();
	return encoder && decoder;
}

/* Start a new transmission. payload must hold 256 bytes. */
JNIEXPORT void JNICALL FN(encoderInit)(JNIEnv *env, jclass, jbyteArray payload)
{
	if (!encoder || env->GetArrayLength(payload) < PAYLOAD_BYTES)
		return;
	jbyte *p = env->GetByteArrayElements(payload, 0);
	if (!p)
		return;
	encoder->init(reinterpret_cast<uint8_t *>(p));
	env->ReleaseByteArrayElements(payload, p, JNI_ABORT);
}

/* Fill pcm[0..count) with the next samples (scaled by gain, clipped).
   Returns true when the transmission has been completely read out. */
JNIEXPORT jboolean JNICALL FN(encoderRead)(JNIEnv *env, jclass, jshortArray pcm, jint count, jfloat gain)
{
	if (!encoder)
		return true;
	float tmp[CHUNK];
	jboolean done = false;
	jshort *out = env->GetShortArrayElements(pcm, 0);
	if (!out)
		return true;
	for (int pos = 0; pos < count; pos += CHUNK) {
		int n = count - pos < CHUNK ? count - pos : CHUNK;
		done = encoder->read(tmp, n);
		for (int i = 0; i < n; ++i) {
			float v = tmp[i] * gain;
			if (v > 1.f) v = 1.f;
			if (v < -1.f) v = -1.f;
			out[pos + i] = (jshort)(v * 32767.f);
		}
	}
	env->ReleaseShortArrayElements(pcm, out, 0);
	return done;
}

/* Feed microphone samples. When a frame was received, its 256 payload bytes
   are written to payload and the decoder's result is returned (>= 0: number
   of corrected bits, -1: CRC failure). Returns -2 when nothing was received. */
JNIEXPORT jint JNICALL FN(decoderFeed)(JNIEnv *env, jclass, jshortArray pcm, jint count, jbyteArray payload)
{
	if (!decoder)
		return -2;
	jint result = -2;
	jshort *in = env->GetShortArrayElements(pcm, 0);
	if (!in)
		return -2;
	for (int i = 0; i < count; ++i) {
		rx_chunk[rx_fill++] = in[i] * (1.f / 32768.f);
		if (rx_fill == CHUNK) {
			rx_fill = 0;
			if (decoder->feed(rx_chunk, CHUNK)) {
				jbyte *p = env->GetByteArrayElements(payload, 0);
				if (p) {
					result = decoder->fetch(reinterpret_cast<uint8_t *>(p));
					env->ReleaseByteArrayElements(payload, p, 0);
				}
			}
		}
	}
	env->ReleaseShortArrayElements(pcm, in, JNI_ABORT);
	return result;
}

/* ENC1 body: returns salt(8) | iv(12) | ciphertext | tag(16), or null. */
JNIEXPORT jbyteArray JNICALL FN(encrypt)(JNIEnv *env, jclass, jbyteArray jpw, jbyteArray jaad, jbyteArray jpt)
{
	jsize pwlen = env->GetArrayLength(jpw), aadlen = env->GetArrayLength(jaad), len = env->GetArrayLength(jpt);
	jsize total = SALT_BYTES + IV_BYTES + len + TAG_BYTES;
	uint8_t *packed = new (std::nothrow) uint8_t[total];
	if (!packed)
		return 0;
	if (rc_random(packed, SALT_BYTES + IV_BYTES)) {
		delete[] packed;
		return 0;
	}
	jbyte *pw = env->GetByteArrayElements(jpw, 0);
	jbyte *aad = env->GetByteArrayElements(jaad, 0);
	jbyte *pt = env->GetByteArrayElements(jpt, 0);
	jbyteArray result = 0;
	if (pw && aad && pt) {
		uint8_t key[32];
		rc_pbkdf2_sha256((uint8_t *)pw, pwlen, packed, SALT_BYTES, ITERATIONS, key, 32);
		rc_aes256gcm_encrypt(key, packed + SALT_BYTES, (uint8_t *)aad, aadlen,
			(uint8_t *)pt, len, packed + SALT_BYTES + IV_BYTES,
			packed + SALT_BYTES + IV_BYTES + len);
		memset(key, 0, sizeof(key));
		result = env->NewByteArray(total);
		if (result)
			env->SetByteArrayRegion(result, 0, total, (jbyte *)packed);
	}
	if (pt) env->ReleaseByteArrayElements(jpt, pt, JNI_ABORT);
	if (aad) env->ReleaseByteArrayElements(jaad, aad, JNI_ABORT);
	if (pw) env->ReleaseByteArrayElements(jpw, pw, JNI_ABORT);
	delete[] packed;
	return result;
}

/* Inverse of encrypt: returns the plaintext, or null if the password or the header is wrong. */
JNIEXPORT jbyteArray JNICALL FN(decrypt)(JNIEnv *env, jclass, jbyteArray jpw, jbyteArray jaad, jbyteArray jpacked)
{
	jsize pwlen = env->GetArrayLength(jpw), aadlen = env->GetArrayLength(jaad), total = env->GetArrayLength(jpacked);
	jsize len = total - SALT_BYTES - IV_BYTES - TAG_BYTES;
	if (len < 0)
		return 0;
	uint8_t *plain = new (std::nothrow) uint8_t[len + 1];
	if (!plain)
		return 0;
	jbyte *pw = env->GetByteArrayElements(jpw, 0);
	jbyte *aad = env->GetByteArrayElements(jaad, 0);
	jbyte *pk = env->GetByteArrayElements(jpacked, 0);
	jbyteArray result = 0;
	if (pw && aad && pk) {
		const uint8_t *packed = (const uint8_t *)pk;
		uint8_t key[32];
		rc_pbkdf2_sha256((uint8_t *)pw, pwlen, packed, SALT_BYTES, ITERATIONS, key, 32);
		int bad = rc_aes256gcm_decrypt(key, packed + SALT_BYTES, (uint8_t *)aad, aadlen,
			packed + SALT_BYTES + IV_BYTES, len, packed + SALT_BYTES + IV_BYTES + len, plain);
		memset(key, 0, sizeof(key));
		if (!bad) {
			result = env->NewByteArray(len);
			if (result)
				env->SetByteArrayRegion(result, 0, len, (jbyte *)plain);
		}
	}
	if (pk) env->ReleaseByteArrayElements(jpacked, pk, JNI_ABORT);
	if (aad) env->ReleaseByteArrayElements(jaad, aad, JNI_ABORT);
	if (pw) env->ReleaseByteArrayElements(jpw, pw, JNI_ABORT);
	memset(plain, 0, len + 1);
	delete[] plain;
	return result;
}

}
