package com.fcribbit.ribbit;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * JNI front end for the web app's C++ modem (webassembly/dsp) and the native
 * AES-256-GCM / PBKDF2-SHA256 used for ENC1 messages.
 *
 * Two builds of the same native code ship in the APK: libribbit_vfp.so for
 * ARMv6+VFP CPUs (Galaxy Y Pro and most 2.3 phones) and a plain soft-float
 * libribbit.so as fallback.
 */
public final class Modem {
	public static final int SAMPLE_RATE = 8000;
	public static final int PAYLOAD_BYTES = 256;
	/** decoderFeed() result when no frame was completed */
	public static final int NOTHING = -2;

	private static boolean loaded;
	private static String variant = "";

	private Modem() {}

	public static synchronized boolean load() {
		if (loaded)
			return true;
		if (cpuHasVfp()) {
			try {
				System.loadLibrary("ribbit_vfp");
				variant = "vfp";
				loaded = nativeCreate();
				return loaded;
			} catch (Throwable t) {
				// fall through to the generic build
			}
		}
		try {
			System.loadLibrary("ribbit");
			variant = "soft";
			loaded = nativeCreate();
		} catch (Throwable t) {
			loaded = false;
		}
		return loaded;
	}

	public static String variant() {
		return variant;
	}

	private static boolean cpuHasVfp() {
		BufferedReader in = null;
		try {
			in = new BufferedReader(new FileReader("/proc/cpuinfo"), 1024);
			String line;
			while ((line = in.readLine()) != null) {
				String l = line.toLowerCase();
				if (l.startsWith("features") && (l.contains(" vfp") || l.contains("\tvfp") || l.contains(":vfp")))
					return true;
			}
		} catch (Exception e) {
			// ignore
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (Exception e) {
					// ignore
				}
			}
		}
		return false;
	}

	private static native boolean nativeCreate();

	/** Start encoding a 256 byte payload. Only call from the transmit thread. */
	public static native void encoderInit(byte[] payload);

	/** Next count samples as 16-bit PCM; returns true once the frame is complete. */
	public static native boolean encoderRead(short[] pcm, int count, float gain);

	/**
	 * Feed microphone samples. Returns NOTHING, -1 (frame received but CRC
	 * failed) or the number of corrected bits (payload filled). Only call
	 * from the receive thread.
	 */
	public static native int decoderFeed(short[] pcm, int count, byte[] payload);

	/** Returns salt(8) | iv(12) | ciphertext | tag(16), or null. */
	public static native byte[] encrypt(byte[] password, byte[] aad, byte[] plaintext);

	/** Returns the plaintext, or null when the password or the header doesn't match. */
	public static native byte[] decrypt(byte[] password, byte[] aad, byte[] packed);
}
