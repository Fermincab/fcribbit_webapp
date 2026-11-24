/*
 * Small self-contained crypto for the Ribbit Android 2.3 port.
 *
 * Android 2.3 ships neither PBKDF2WithHmacSHA256 nor AES/GCM, so the
 * primitives the web app gets from WebCrypto are implemented here:
 *   SHA-256, HMAC-SHA256, PBKDF2-HMAC-SHA256, AES-256, AES-256-GCM (96-bit IV).
 * Running them natively also keeps the 100000 PBKDF2 iterations fast on ARMv6.
 */
#pragma once
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void rc_sha256(const uint8_t *data, size_t len, uint8_t out[32]);

void rc_hmac_sha256(const uint8_t *key, size_t keylen,
		const uint8_t *data, size_t len, uint8_t out[32]);

void rc_pbkdf2_sha256(const uint8_t *password, size_t pwlen,
		const uint8_t *salt, size_t saltlen, uint32_t iterations,
		uint8_t *out, size_t outlen);

/* ciphertext has the same length as plaintext; tag is 16 bytes */
void rc_aes256gcm_encrypt(const uint8_t key[32], const uint8_t iv[12],
		const uint8_t *aad, size_t aadlen,
		const uint8_t *plaintext, size_t len,
		uint8_t *ciphertext, uint8_t tag[16]);

/* returns 0 when the tag matches (plaintext written), -1 otherwise (plaintext zeroed) */
int rc_aes256gcm_decrypt(const uint8_t key[32], const uint8_t iv[12],
		const uint8_t *aad, size_t aadlen,
		const uint8_t *ciphertext, size_t len, const uint8_t tag[16],
		uint8_t *plaintext);

/* fills buf from /dev/urandom; returns 0 on success */
int rc_random(uint8_t *buf, size_t len);

#ifdef __cplusplus
}
#endif
