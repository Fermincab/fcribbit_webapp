package com.fcribbit.ribbit;

import android.util.Base64;

/**
 * Port of window.RibbitCrypto from the web app's index.html.
 *
 * Encrypted body: "ENC1:" + base64(salt(8) | iv(12) | ciphertext+tag(16)).
 * Key: PBKDF2-SHA256(password, salt, 100000) -> AES-256-GCM. The readable
 * header (name|callsign|grid|phone) is the GCM additional data.
 */
public final class RibbitCrypto {
	public static final String PREFIX = "ENC1:";
	public static final int SALT_BYTES = 8;
	public static final int IV_BYTES = 12;
	public static final int TAG_BYTES = 16;
	public static final int OVERHEAD = SALT_BYTES + IV_BYTES + TAG_BYTES;

	private RibbitCrypto() {}

	/** How many plaintext bytes fit when room bytes are free in the payload (same formula as the web app). */
	public static int maxPlaintextBytes(int room) {
		return (int) Math.floor((room - PREFIX.length()) / 4.0) * 3 - OVERHEAD;
	}

	public static boolean isEncrypted(String s) {
		return s != null && s.startsWith(PREFIX);
	}

	/** Returns the ENC1 body, or null on failure. Slow (PBKDF2): call off the UI thread. */
	public static String encrypt(String text, String password, String header) {
		byte[] packed = Modem.encrypt(Payload.utf8(password), Payload.utf8(header), Payload.utf8(text));
		if (packed == null)
			return null;
		return PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP);
	}

	/** Returns the plaintext, or null when the password/header is wrong or the body is damaged. */
	public static String decrypt(String body, String password, String header) {
		byte[] packed;
		try {
			packed = Base64.decode(body.substring(PREFIX.length()).trim(), Base64.DEFAULT);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (packed.length < OVERHEAD)
			return null;
		byte[] plain = Modem.decrypt(Payload.utf8(password), Payload.utf8(header), packed);
		if (plain == null)
			return null;
		return Payload.fromUtf8(plain, plain.length);
	}
}
