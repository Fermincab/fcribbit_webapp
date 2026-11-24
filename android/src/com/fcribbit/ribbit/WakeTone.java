package com.fcribbit.ribbit;

/**
 * Wake-up signal for sleeping phones: a continuous-phase two-tone sequence
 * (1000 Hz / 1800 Hz, switching every 100 ms), and a cheap Goertzel detector
 * for it. The pattern is easy to tell apart from voice, noise and Ribbit's own
 * OFDM frames, which spread their energy over many carriers.
 */
public final class WakeTone {
	public static final int RATE = 8000;
	static final double F1 = 1000, F2 = 1800;
	static final int SEGMENT = RATE / 10; // 100 ms per tone
	static final int BLOCK = 200;         // 25 ms analysis blocks: 1000 and 1800 Hz fall on exact bins
	static final int WINDOW = 20;         // blocks looked at (0.5 s)
	static final double MIN_RATIO = 0.45; // share of the block's energy in the tone's bin
	static final double MIN_RMS = 0.003;  // about -50 dBFS

	private WakeTone() {}

	/** ms of tone as 16-bit PCM with 20 ms fade in/out */
	public static short[] generate(int ms, float gain) {
		int n = RATE * ms / 1000;
		short[] out = new short[n];
		double phase = 0;
		int fade = RATE / 50;
		double amp = 0.7 * Math.min(gain, 1f) * 32767;
		for (int i = 0; i < n; ++i) {
			double f = (i / SEGMENT) % 2 == 0 ? F1 : F2;
			phase += 2 * Math.PI * f / RATE;
			if (phase > 2 * Math.PI)
				phase -= 2 * Math.PI;
			double env = 1;
			if (i < fade)
				env = i / (double) fade;
			else if (i > n - fade)
				env = (n - i) / (double) fade;
			out[i] = (short) (amp * env * Math.sin(phase));
		}
		return out;
	}

	/** Feed it audio; detected() turns true while the wake pattern is present. */
	public static final class Detector {
		private final double c1 = 2 * Math.cos(2 * Math.PI * F1 / RATE);
		private final double c2 = 2 * Math.cos(2 * Math.PI * F2 / RATE);
		private final int[] history = new int[WINDOW]; // 0 none, 1 = F1, 2 = F2
		private int head;
		private double s1a, s1b, s2a, s2b, energy;
		private int fill;
		private boolean detected;

		public void reset() {
			for (int i = 0; i < WINDOW; ++i)
				history[i] = 0;
			s1a = s1b = s2a = s2b = energy = 0;
			fill = 0;
			detected = false;
		}

		/** returns true if the pattern was seen somewhere in these samples */
		public boolean process(short[] pcm, int offset, int count) {
			boolean seen = false;
			for (int i = 0; i < count; ++i) {
				double x = pcm[offset + i] / 32768.0;
				double t1 = x + c1 * s1a - s1b;
				s1b = s1a;
				s1a = t1;
				double t2 = x + c2 * s2a - s2b;
				s2b = s2a;
				s2a = t2;
				energy += x * x;
				if (++fill == BLOCK) {
					endBlock();
					seen |= detected;
				}
			}
			return seen;
		}

		public boolean detected() {
			return detected;
		}

		private void endBlock() {
			double p1 = s1a * s1a + s1b * s1b - c1 * s1a * s1b;
			double p2 = s2a * s2a + s2b * s2b - c2 * s2a * s2b;
			// a full-scale sine of amplitude A gives |X|^2 = (A N / 2)^2 and energy = A^2 N / 2
			double norm = energy * BLOCK / 2;
			int kind = 0;
			if (Math.sqrt(energy / BLOCK) >= MIN_RMS && norm > 0) {
				if (p1 / norm >= MIN_RATIO)
					kind = 1;
				else if (p2 / norm >= MIN_RATIO)
					kind = 2;
			}
			history[head] = kind;
			head = (head + 1) % WINDOW;
			int n1 = 0, n2 = 0;
			for (int k : history) {
				if (k == 1)
					++n1;
				else if (k == 2)
					++n2;
			}
			detected = n1 >= 4 && n2 >= 4 && n1 + n2 >= 14;
			s1a = s1b = s2a = s2b = energy = 0;
			fill = 0;
		}
	}
}
