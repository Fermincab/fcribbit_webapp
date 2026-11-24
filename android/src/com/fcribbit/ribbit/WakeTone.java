package com.fcribbit.ribbit;

import java.security.MessageDigest;
import java.util.Random;

/**
 * Session wake-up signal that sounds like noise.
 *
 * The signal is a pseudo-random sequence of PERIOD samples (128 ms), band-limited to
 * 400-2600 Hz with a flat spectrum and random phases, repeated for as long as the call
 * lasts. To the ear (and to anyone monitoring the frequency) it is a plain hiss. The
 * phases come from the group code, so each group has its own sequence and phones with
 * another code neither wake up on it nor recognise it.
 *
 * Detection folds WINDOW samples (512 ms, four periods) onto one period, so the
 * repeating sequence adds up while noise doesn't, then correlates that against the
 * known sequence with phase-only weighting (PHAT). A match gives a sharp peak; noise,
 * voice, steady tones, Ribbit frames or another group's sequence give none.
 */
public final class WakeTone {
	public static final int RATE = 8000;
	static final int PERIOD = 1024;                 // 128 ms
	public static final int WINDOW = PERIOD * 4;    // 512 ms analysed at a time
	public static final int WINDOW_MS = WINDOW * 1000 / RATE;
	static final int HOP = PERIOD * 2;              // continuous listening: evaluate every 256 ms
	static final int LOW_BIN = 400 * PERIOD / RATE + 1;   // 400 Hz
	static final int HIGH_BIN = 2600 * PERIOD / RATE;     // 2600 Hz
	static final double THRESHOLD = 0.3;            // normalised correlation peak (noise stays below ~0.2)
	static final double MIN_RMS = 0.0005;           // about -66 dBFS: ignore digital silence

	public static final String DEFAULT_CODE = "ribbit";

	private final double[] cosPhase = new double[PERIOD];
	private final double[] sinPhase = new double[PERIOD];
	private final short[] period = new short[PERIOD];

	public WakeTone(String groupCode) {
		Random random = new Random(seed(groupCode));
		double[] re = new double[PERIOD], im = new double[PERIOD];
		for (int k = LOW_BIN; k <= HIGH_BIN; ++k) {
			double phi = 2 * Math.PI * random.nextDouble();
			cosPhase[k] = Math.cos(phi);
			sinPhase[k] = Math.sin(phi);
			re[k] = cosPhase[k];
			im[k] = sinPhase[k];
			re[PERIOD - k] = cosPhase[k];
			im[PERIOD - k] = -sinPhase[k];
		}
		fft(re, im, true);
		double peak = 0;
		for (int i = 0; i < PERIOD; ++i)
			peak = Math.max(peak, Math.abs(re[i]));
		for (int i = 0; i < PERIOD; ++i)
			period[i] = (short) Math.round(re[i] / peak * 32767);
	}

	static long seed(String groupCode) {
		String code = groupCode == null ? "" : groupCode.trim();
		if (code.length() == 0)
			code = DEFAULT_CODE;
		try {
			byte[] h = MessageDigest.getInstance("SHA-256").digest(("ribbit-wake-v1:" + code).getBytes("UTF-8"));
			long s = 0;
			for (int i = 0; i < 8; ++i)
				s = (s << 8) | (h[i] & 0xff);
			return s;
		} catch (Exception e) {
			return code.hashCode();
		}
	}

	/** ms of the wake signal as 16-bit PCM, peaks at 80% of gain, 20 ms fade in/out */
	public short[] generate(int ms, float gain) {
		int n = RATE * ms / 1000;
		short[] out = new short[n];
		int fade = RATE / 50;
		double amp = 0.8 * Math.min(gain, 1f);
		for (int i = 0; i < n; ++i) {
			double env = 1;
			if (i < fade)
				env = i / (double) fade;
			else if (i > n - fade)
				env = (n - i) / (double) fade;
			out[i] = (short) (period[i % PERIOD] * amp * env);
		}
		return out;
	}

	/** a new detector for this group's signal (use this, not wake.new Detector(): on Android 2.3
	 *  javac's null check for qualified inner class creation needs java.util.Objects) */
	public Detector detector() {
		return new Detector();
	}

	/** Feed it audio; process() says whether the group's wake signal was found. */
	public final class Detector {
		private final double[] history = new double[WINDOW];
		private int pos, filled, sinceEval;
		private double lastScore;
		private final double[] re = new double[PERIOD], im = new double[PERIOD];

		public void reset() {
			pos = filled = sinceEval = 0;
		}

		/** correlation score of the last evaluation (0..1), for diagnostics */
		public double lastScore() {
			return lastScore;
		}

		/** true if the signal was found in one of the windows completed by these samples */
		public boolean process(short[] pcm, int offset, int count) {
			boolean seen = false;
			for (int i = 0; i < count; ++i) {
				history[pos] = pcm[offset + i] / 32768.0;
				pos = (pos + 1) % WINDOW;
				if (filled < WINDOW)
					++filled;
				if (filled == WINDOW && ++sinceEval >= HOP) {
					sinceEval = 0;
					seen |= evaluate();
				}
			}
			return seen;
		}

		/** evaluate the current window right away (used by the short sleep-mode check) */
		public boolean evaluateNow() {
			return filled == WINDOW && evaluate();
		}

		private boolean evaluate() {
			double energy = 0;
			for (int i = 0; i < PERIOD; ++i) {
				re[i] = 0;
				im[i] = 0;
			}
			for (int i = 0; i < WINDOW; ++i) {
				double x = history[(pos + i) % WINDOW];
				energy += x * x;
				re[i & (PERIOD - 1)] += x; // fold: the window starts at a whole period
			}
			if (Math.sqrt(energy / WINDOW) < MIN_RMS) {
				lastScore = 0;
				return false;
			}
			fft(re, im, false);
			int bins = 0;
			for (int k = 0; k <= PERIOD / 2; ++k) {
				if (k < LOW_BIN || k > HIGH_BIN) {
					re[k] = im[k] = 0;
					if (k > 0 && k < PERIOD / 2)
						re[PERIOD - k] = im[PERIOD - k] = 0;
					continue;
				}
				// X(k) * conj(T(k)), keep the phase only
				double a = re[k] * cosPhase[k] + im[k] * sinPhase[k];
				double b = im[k] * cosPhase[k] - re[k] * sinPhase[k];
				double mag = Math.sqrt(a * a + b * b);
				if (mag > 0) {
					a /= mag;
					b /= mag;
				}
				re[k] = a;
				im[k] = b;
				re[PERIOD - k] = a;
				im[PERIOD - k] = -b;
				bins += 2;
			}
			fft(re, im, true);
			double best = 0;
			for (int i = 0; i < PERIOD; ++i)
				best = Math.max(best, re[i]);
			lastScore = best / bins;
			return lastScore >= THRESHOLD;
		}
	}

	/** in-place radix-2 FFT (no scaling) */
	static void fft(double[] re, double[] im, boolean inverse) {
		int n = re.length;
		for (int i = 1, j = 0; i < n; ++i) {
			int bit = n >> 1;
			for (; (j & bit) != 0; bit >>= 1)
				j ^= bit;
			j ^= bit;
			if (i < j) {
				double t = re[i];
				re[i] = re[j];
				re[j] = t;
				t = im[i];
				im[i] = im[j];
				im[j] = t;
			}
		}
		for (int len = 2; len <= n; len <<= 1) {
			double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
			double wr = Math.cos(ang), wi = Math.sin(ang);
			for (int i = 0; i < n; i += len) {
				double cr = 1, ci = 0;
				for (int j = 0; j < len / 2; ++j) {
					int a = i + j, b = i + j + len / 2;
					double xr = re[b] * cr - im[b] * ci;
					double xi = re[b] * ci + im[b] * cr;
					re[b] = re[a] - xr;
					im[b] = im[a] - xi;
					re[a] += xr;
					im[a] += xi;
					double t = cr * wr - ci * wi;
					ci = cr * wi + ci * wr;
					cr = t;
				}
			}
		}
	}
}
