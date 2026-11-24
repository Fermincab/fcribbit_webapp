/*
 * SHA-256, HMAC-SHA256, PBKDF2-HMAC-SHA256 and AES-256-GCM for the Ribbit
 * Android 2.3 port. Straightforward reference-style implementations sized for
 * short chat messages (a Ribbit payload is 256 bytes).
 */
#include "ribbit_crypto.h"
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>

/* ---------------------------------------------------------------- SHA-256 */

typedef struct {
	uint32_t h[8];
	uint64_t total;
	uint8_t block[64];
	size_t used;
} sha256_ctx;

static const uint32_t K256[64] = {
	0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
	0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
	0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
	0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
	0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
	0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
	0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
	0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
};

#define ROR32(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static void sha256_compress(uint32_t h[8], const uint8_t p[64])
{
	uint32_t w[64], a, b, c, d, e, f, g, k, t1, t2;
	int i;
	for (i = 0; i < 16; ++i)
		w[i] = (uint32_t)p[4 * i] << 24 | (uint32_t)p[4 * i + 1] << 16 |
			(uint32_t)p[4 * i + 2] << 8 | p[4 * i + 3];
	for (i = 16; i < 64; ++i) {
		uint32_t s0 = ROR32(w[i - 15], 7) ^ ROR32(w[i - 15], 18) ^ (w[i - 15] >> 3);
		uint32_t s1 = ROR32(w[i - 2], 17) ^ ROR32(w[i - 2], 19) ^ (w[i - 2] >> 10);
		w[i] = w[i - 16] + s0 + w[i - 7] + s1;
	}
	a = h[0]; b = h[1]; c = h[2]; d = h[3]; e = h[4]; f = h[5]; g = h[6]; k = h[7];
	for (i = 0; i < 64; ++i) {
		t1 = k + (ROR32(e, 6) ^ ROR32(e, 11) ^ ROR32(e, 25)) + ((e & f) ^ (~e & g)) + K256[i] + w[i];
		t2 = (ROR32(a, 2) ^ ROR32(a, 13) ^ ROR32(a, 22)) + ((a & b) ^ (a & c) ^ (b & c));
		k = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
	}
	h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += k;
}

static void sha256_init(sha256_ctx *c)
{
	static const uint32_t iv[8] = {
		0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
		0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
	};
	memcpy(c->h, iv, sizeof(iv));
	c->total = 0;
	c->used = 0;
}

static void sha256_update(sha256_ctx *c, const uint8_t *p, size_t len)
{
	c->total += len;
	while (len) {
		size_t n = 64 - c->used;
		if (n > len)
			n = len;
		memcpy(c->block + c->used, p, n);
		c->used += n;
		p += n;
		len -= n;
		if (c->used == 64) {
			sha256_compress(c->h, c->block);
			c->used = 0;
		}
	}
}

static void sha256_final(sha256_ctx *c, uint8_t out[32])
{
	uint64_t bits = c->total * 8;
	uint8_t pad = 0x80, zero = 0, len[8];
	int i;
	sha256_update(c, &pad, 1);
	while (c->used != 56)
		sha256_update(c, &zero, 1);
	for (i = 0; i < 8; ++i)
		len[i] = (uint8_t)(bits >> (56 - 8 * i));
	sha256_update(c, len, 8);
	for (i = 0; i < 8; ++i) {
		out[4 * i] = (uint8_t)(c->h[i] >> 24);
		out[4 * i + 1] = (uint8_t)(c->h[i] >> 16);
		out[4 * i + 2] = (uint8_t)(c->h[i] >> 8);
		out[4 * i + 3] = (uint8_t)c->h[i];
	}
}

void rc_sha256(const uint8_t *data, size_t len, uint8_t out[32])
{
	sha256_ctx c;
	sha256_init(&c);
	sha256_update(&c, data, len);
	sha256_final(&c, out);
}

/* ------------------------------------------------------------ HMAC/PBKDF2 */

typedef struct {
	sha256_ctx inner, outer;
} hmac_ctx;

static void hmac_init(hmac_ctx *m, const uint8_t *key, size_t keylen)
{
	uint8_t k[64], pad[64];
	int i;
	memset(k, 0, sizeof(k));
	if (keylen > 64)
		rc_sha256(key, keylen, k);
	else if (keylen)
		memcpy(k, key, keylen);
	for (i = 0; i < 64; ++i)
		pad[i] = k[i] ^ 0x36;
	sha256_init(&m->inner);
	sha256_update(&m->inner, pad, 64);
	for (i = 0; i < 64; ++i)
		pad[i] = k[i] ^ 0x5c;
	sha256_init(&m->outer);
	sha256_update(&m->outer, pad, 64);
	memset(k, 0, sizeof(k));
	memset(pad, 0, sizeof(pad));
}

/* HMAC of data using precomputed key states (the contexts are copied, not consumed) */
static void hmac_run(const hmac_ctx *m, const uint8_t *data, size_t len, uint8_t out[32])
{
	sha256_ctx c = m->inner;
	uint8_t ih[32];
	sha256_update(&c, data, len);
	sha256_final(&c, ih);
	c = m->outer;
	sha256_update(&c, ih, 32);
	sha256_final(&c, out);
}

void rc_hmac_sha256(const uint8_t *key, size_t keylen,
		const uint8_t *data, size_t len, uint8_t out[32])
{
	hmac_ctx m;
	hmac_init(&m, key, keylen);
	hmac_run(&m, data, len, out);
	memset(&m, 0, sizeof(m));
}

void rc_pbkdf2_sha256(const uint8_t *password, size_t pwlen,
		const uint8_t *salt, size_t saltlen, uint32_t iterations,
		uint8_t *out, size_t outlen)
{
	hmac_ctx m;
	uint8_t u[32], t[32], cnt[4];
	uint32_t block = 1, it;
	int i;
	hmac_init(&m, password, pwlen);
	while (outlen) {
		sha256_ctx c = m.inner;
		uint8_t ih[32];
		size_t n = outlen < 32 ? outlen : 32;
		cnt[0] = (uint8_t)(block >> 24);
		cnt[1] = (uint8_t)(block >> 16);
		cnt[2] = (uint8_t)(block >> 8);
		cnt[3] = (uint8_t)block;
		sha256_update(&c, salt, saltlen);
		sha256_update(&c, cnt, 4);
		sha256_final(&c, ih);
		c = m.outer;
		sha256_update(&c, ih, 32);
		sha256_final(&c, u);
		memcpy(t, u, 32);
		for (it = 1; it < iterations; ++it) {
			hmac_run(&m, u, 32, u);
			for (i = 0; i < 32; ++i)
				t[i] ^= u[i];
		}
		memcpy(out, t, n);
		out += n;
		outlen -= n;
		++block;
	}
	memset(&m, 0, sizeof(m));
	memset(u, 0, sizeof(u));
	memset(t, 0, sizeof(t));
}

/* ---------------------------------------------------------------- AES-256 */

static const uint8_t SBOX[256] = {
	0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
	0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
	0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
	0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
	0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
	0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
	0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
	0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
	0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
	0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
	0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
	0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
	0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
	0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
	0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
	0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
};

typedef struct {
	uint8_t rk[240]; /* 15 round keys */
} aes256_ctx;

static uint8_t xtime(uint8_t x)
{
	return (uint8_t)((x << 1) ^ ((x >> 7) * 0x1b));
}

static void aes256_init(aes256_ctx *a, const uint8_t key[32])
{
	uint8_t rcon = 1, t[4];
	int i;
	memcpy(a->rk, key, 32);
	for (i = 8; i < 60; ++i) {
		memcpy(t, a->rk + 4 * (i - 1), 4);
		if (i % 8 == 0) {
			uint8_t tmp = t[0];
			t[0] = SBOX[t[1]] ^ rcon;
			t[1] = SBOX[t[2]];
			t[2] = SBOX[t[3]];
			t[3] = SBOX[tmp];
			rcon = xtime(rcon);
		} else if (i % 8 == 4) {
			t[0] = SBOX[t[0]];
			t[1] = SBOX[t[1]];
			t[2] = SBOX[t[2]];
			t[3] = SBOX[t[3]];
		}
		a->rk[4 * i] = a->rk[4 * (i - 8)] ^ t[0];
		a->rk[4 * i + 1] = a->rk[4 * (i - 8) + 1] ^ t[1];
		a->rk[4 * i + 2] = a->rk[4 * (i - 8) + 2] ^ t[2];
		a->rk[4 * i + 3] = a->rk[4 * (i - 8) + 3] ^ t[3];
	}
}

static void aes256_encrypt_block(const aes256_ctx *a, const uint8_t in[16], uint8_t out[16])
{
	uint8_t s[16], t[16];
	int r, i, c;
	for (i = 0; i < 16; ++i)
		s[i] = in[i] ^ a->rk[i];
	for (r = 1; r <= 14; ++r) {
		/* SubBytes + ShiftRows (state is column-major: s[4*col + row]) */
		for (c = 0; c < 4; ++c)
			for (i = 0; i < 4; ++i)
				t[4 * c + i] = SBOX[s[4 * ((c + i) & 3) + i]];
		if (r != 14) {
			/* MixColumns */
			for (c = 0; c < 4; ++c) {
				uint8_t *p = t + 4 * c;
				uint8_t a0 = p[0], a1 = p[1], a2 = p[2], a3 = p[3];
				uint8_t all = a0 ^ a1 ^ a2 ^ a3;
				p[0] = a0 ^ all ^ xtime(a0 ^ a1);
				p[1] = a1 ^ all ^ xtime(a1 ^ a2);
				p[2] = a2 ^ all ^ xtime(a2 ^ a3);
				p[3] = a3 ^ all ^ xtime(a3 ^ a0);
			}
		}
		for (i = 0; i < 16; ++i)
			s[i] = t[i] ^ a->rk[16 * r + i];
	}
	memcpy(out, s, 16);
}

/* ------------------------------------------------------------------- GCM */

/* X = X * Y in GF(2^128) with the GCM bit order */
static void gf128_mul(uint8_t x[16], const uint8_t y[16])
{
	uint8_t z[16], v[16];
	int i, j, k;
	memset(z, 0, 16);
	memcpy(v, y, 16);
	for (i = 0; i < 16; ++i) {
		for (j = 7; j >= 0; --j) {
			if ((x[i] >> j) & 1)
				for (k = 0; k < 16; ++k)
					z[k] ^= v[k];
			{
				int lsb = v[15] & 1;
				for (k = 15; k > 0; --k)
					v[k] = (uint8_t)((v[k] >> 1) | (v[k - 1] << 7));
				v[0] >>= 1;
				if (lsb)
					v[0] ^= 0xe1;
			}
		}
	}
	memcpy(x, z, 16);
}

static void ghash_update(uint8_t y[16], const uint8_t h[16], const uint8_t *p, size_t len)
{
	while (len) {
		size_t n = len < 16 ? len : 16, i;
		for (i = 0; i < n; ++i)
			y[i] ^= p[i];
		gf128_mul(y, h);
		p += n;
		len -= n;
	}
}

static void gcm_core(const uint8_t key[32], const uint8_t iv[12],
		const uint8_t *aad, size_t aadlen,
		const uint8_t *in, size_t len, uint8_t *out,
		int encrypting, uint8_t tag[16])
{
	aes256_ctx a;
	uint8_t h[16], j0[16], ctr[16], ks[16], y[16], lens[16];
	uint64_t abits = (uint64_t)aadlen * 8, cbits = (uint64_t)len * 8;
	size_t off = 0, i;
	uint32_t counter;

	aes256_init(&a, key);
	memset(h, 0, 16);
	aes256_encrypt_block(&a, h, h);
	memcpy(j0, iv, 12);
	j0[12] = 0; j0[13] = 0; j0[14] = 0; j0[15] = 1;

	memset(y, 0, 16);
	ghash_update(y, h, aad, aadlen);
	if (!encrypting)
		ghash_update(y, h, in, len);

	memcpy(ctr, j0, 16);
	counter = 1;
	while (off < len) {
		size_t n = len - off < 16 ? len - off : 16;
		++counter;
		ctr[12] = (uint8_t)(counter >> 24);
		ctr[13] = (uint8_t)(counter >> 16);
		ctr[14] = (uint8_t)(counter >> 8);
		ctr[15] = (uint8_t)counter;
		aes256_encrypt_block(&a, ctr, ks);
		for (i = 0; i < n; ++i)
			out[off + i] = in[off + i] ^ ks[i];
		off += n;
	}
	if (encrypting)
		ghash_update(y, h, out, len);

	for (i = 0; i < 8; ++i) {
		lens[i] = (uint8_t)(abits >> (56 - 8 * i));
		lens[8 + i] = (uint8_t)(cbits >> (56 - 8 * i));
	}
	ghash_update(y, h, lens, 16);
	aes256_encrypt_block(&a, j0, ks);
	for (i = 0; i < 16; ++i)
		tag[i] = ks[i] ^ y[i];
	memset(&a, 0, sizeof(a));
	memset(ks, 0, sizeof(ks));
	memset(h, 0, sizeof(h));
}

void rc_aes256gcm_encrypt(const uint8_t key[32], const uint8_t iv[12],
		const uint8_t *aad, size_t aadlen,
		const uint8_t *plaintext, size_t len,
		uint8_t *ciphertext, uint8_t tag[16])
{
	gcm_core(key, iv, aad, aadlen, plaintext, len, ciphertext, 1, tag);
}

int rc_aes256gcm_decrypt(const uint8_t key[32], const uint8_t iv[12],
		const uint8_t *aad, size_t aadlen,
		const uint8_t *ciphertext, size_t len, const uint8_t tag[16],
		uint8_t *plaintext)
{
	uint8_t expect[16], diff = 0;
	int i;
	gcm_core(key, iv, aad, aadlen, ciphertext, len, plaintext, 0, expect);
	for (i = 0; i < 16; ++i)
		diff |= expect[i] ^ tag[i];
	if (diff) {
		memset(plaintext, 0, len);
		return -1;
	}
	return 0;
}

/* ---------------------------------------------------------------- random */

int rc_random(uint8_t *buf, size_t len)
{
	int fd = open("/dev/urandom", O_RDONLY);
	if (fd < 0)
		return -1;
	while (len) {
		ssize_t n = read(fd, buf, len);
		if (n < 0) {
			if (errno == EINTR)
				continue;
			close(fd);
			return -1;
		}
		if (n == 0) {
			close(fd);
			return -1;
		}
		buf += n;
		len -= (size_t)n;
	}
	close(fd);
	return 0;
}
