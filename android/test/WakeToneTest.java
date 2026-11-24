import com.fcribbit.ribbit.*;
import java.util.Random;

/**
 * Host test for the noise-like wake signal: detection through a simulated FM radio
 * channel, at low levels, below the noise, clipped, with clock offsets and partial
 * overlap; no detection on noise, voice-like audio, steady tones, Ribbit frames or
 * another group's signal.
 */
public class WakeToneTest {
	static int fails = 0;
	static final int RATE = 8000;
	static final WakeTone GROUP = new WakeTone("marcha-norte");

	static void check(String what, boolean ok) {
		System.out.println((ok ? "ok   " : "FAIL ") + what);
		if (!ok) fails++;
	}

	/** like checkOnce(): exactly one window, evaluated once */
	static boolean checkWindow(WakeTone w, short[] s) {
		WakeTone.Detector d = w.detector();
		short[] win = new short[WakeTone.WINDOW];
		System.arraycopy(s, 0, win, 0, Math.min(s.length, win.length));
		d.process(win, 0, win.length);
		return d.evaluateNow();
	}

	/** like continuous listening */
	static boolean stream(WakeTone w, short[] s) {
		WakeTone.Detector d = w.detector();
		boolean seen = false;
		for (int i = 0; i < s.length; i += 400) seen |= d.process(s, i, Math.min(400, s.length - i));
		return seen;
	}

	static double[] toD(short[] s) { double[] d = new double[s.length]; for (int i = 0; i < s.length; i++) d[i] = s[i] / 32768.0; return d; }
	static short[] toS(double[] d) { short[] s = new short[d.length]; for (int i = 0; i < d.length; i++) s[i] = (short) Math.max(-32768, Math.min(32767, Math.round(d[i] * 32768))); return s; }

	static double[] gainNoise(double[] x, double g, double noiseRms, long seed) {
		Random r = new Random(seed); double[] y = new double[x.length];
		for (int i = 0; i < x.length; i++) y[i] = x[i] * g + r.nextGaussian() * noiseRms;
		return y;
	}

	/** one-pole high/low pass helpers (bilinear-ish) */
	static double[] lowpass(double[] x, double fc) { double a = Math.exp(-2 * Math.PI * fc / RATE); double[] y = new double[x.length]; double s = 0; for (int i = 0; i < x.length; i++) { s = (1 - a) * x[i] + a * s; y[i] = s; } return y; }
	static double[] highpass(double[] x, double fc) { double[] l = lowpass(x, fc); double[] y = new double[x.length]; for (int i = 0; i < x.length; i++) y[i] = x[i] - l[i]; return y; }

	/** a UV-5R style FM link: pre-emphasis + deviation limiter + 300-3000 Hz filters + de-emphasis */
	static double[] fmChannel(double[] x, double drive) {
		double[] y = new double[x.length];
		double prev = 0;
		for (int i = 0; i < x.length; i++) { y[i] = (x[i] - 0.7 * prev) * drive; prev = x[i]; } // pre-emphasis
		for (int i = 0; i < y.length; i++) y[i] = Math.max(-0.5, Math.min(0.5, y[i]));       // deviation limiter
		y = highpass(highpass(y, 300), 300);
		y = lowpass(lowpass(lowpass(y, 3000), 3000), 3000);
		y = lowpass(y, 750);                                                                  // de-emphasis
		double m = 0; for (double v : y) m = Math.max(m, Math.abs(v));
		for (int i = 0; i < y.length; i++) y[i] = y[i] / m * 0.5;
		return y;
	}

	/** receive with a sample clock off by ppm (linear interpolation) */
	static double[] resample(double[] x, double ppm) {
		double ratio = 1 + ppm * 1e-6; int n = (int) (x.length / ratio) - 2; double[] y = new double[n];
		for (int i = 0; i < n; i++) { double t = i * ratio; int k = (int) t; double f = t - k; y[i] = x[k] * (1 - f) + x[k + 1] * f; }
		return y;
	}

	static short[] at(short[] s, int offset) { short[] o = new short[s.length - offset]; System.arraycopy(s, offset, o, 0, o.length); return o; }

	public static void main(String[] a) {
		short[] sig = GROUP.generate(3000, 0.75f);
		double[] d = toD(sig);

		check("clean, one 512 ms window", checkWindow(GROUP, at(sig, 333)));
		check("clean, continuous listening", stream(GROUP, sig));
		check("level 2%", checkWindow(GROUP, at(toS(gainNoise(d, 0.02, 0, 1)), 777)));
		int snrOk = 0;
		for (int s = 0; s < 50; s++) if (checkWindow(GROUP, at(toS(gainNoise(d, 0.25, 0.1, 100 + s)), 500 + 97 * s))) snrOk++;
		check("SNR 0 dB (noise as loud as the signal): " + snrOk + "/50 windows", snrOk >= 48);
		int low = 0;
		for (int s = 0; s < 50; s++) if (checkWindow(GROUP, at(toS(gainNoise(d, 0.1, 0.1, 200 + s)), 500 + 97 * s))) low++;
		check("SNR -8 dB (signal below the noise): " + low + "/50 windows", low >= 40);
		check("clipped hard (x10)", checkWindow(GROUP, at(toS(gainNoise(d, 10, 0, 2)), 1234)));
		int fm = 0;
		for (int s = 0; s < 50; s++) if (checkWindow(GROUP, at(toS(gainNoise(fmChannel(d, 1 + s % 5), 1, 0.02, 300 + s)), 200 + 91 * s))) fm++;
		check("through a simulated FM radio link (filters, emphasis, limiter, noise): " + fm + "/50", fm >= 48);
		for (double ppm : new double[] { -500, -200, 200, 500 }) {
			int ok = 0;
			for (int s = 0; s < 20; s++) if (checkWindow(GROUP, at(toS(gainNoise(resample(fmChannel(d, 2), ppm), 1, 0.02, 400 + s)), 300 + 113 * s))) ok++;
			check("FM link + sample clock " + (ppm > 0 ? "+" : "") + (int) ppm + " ppm: " + ok + "/20", ok >= 19);
		}
		// partial overlap: the signal starts or ends inside the window
		for (int ms : new int[] { 400, 350, 300 }) {
			int n = RATE * ms / 1000; short[] w = new short[WakeTone.WINDOW]; System.arraycopy(sig, 0, w, WakeTone.WINDOW - n, n);
			check(ms + " ms of signal at the end of a window", checkWindow(GROUP, toS(gainNoise(toD(w), 1, 0.003, 5))));
		}
		int minMs = 0;
		for (int ms = 10; ms <= 512; ms += 10) {
			int n = RATE * ms / 1000; short[] w = new short[WakeTone.WINDOW]; System.arraycopy(sig, 0, w, WakeTone.WINDOW - n, n);
			if (checkWindow(GROUP, toS(gainNoise(toD(w), 1, 0.01, 11)))) { minMs = ms; break; }
		}
		System.out.println("     shortest overlap detected (with some noise): " + minMs + " ms");
		check("an overlap of 300 ms or less is enough", minMs > 0 && minMs <= 300);
		// margin: highest score noise ever reaches vs the threshold
		WakeTone.Detector dn = GROUP.detector(); short[] nz = toS(gainNoise(new double[RATE * 600], 0, 0.2, 12));
		double worst = 0; for (int i = 0; i < nz.length; i += 400) { dn.process(nz, i, 400); worst = Math.max(worst, dn.lastScore()); }
		System.out.printf("     highest score on 10 min of noise: %.3f (threshold 0.3)%n", worst);
		check("noise stays below the threshold with margin (0.3 is > 7 sigma of noise)", worst < 0.25);

		// false alarms
		WakeTone other = new WakeTone("otro-grupo");
		int cross = 0; for (int s = 0; s < 50; s++) if (checkWindow(GROUP, at(other.generate(1000, 0.75f), 37 * s))) cross++;
		check("another group's signal: no wake-up (" + cross + "/50)", cross == 0);
		check("default code differs from the group's", !stream(GROUP, new WakeTone("").generate(3000, 0.75f)));
		check("10 min of white noise: no detection", !stream(GROUP, toS(gainNoise(new double[RATE * 600], 0, 0.2, 6))));
		check("10 min of FM-style noise (filtered, emphasised): no detection", !stream(GROUP, toS(fmChannel(gainNoise(new double[RATE * 600], 0, 0.3, 7), 3))));
		for (double f : new double[] { 1000, 1750, 1800, 125 * 8, 7.8125 * 100 }) {
			short[] s = new short[RATE * 30]; for (int i = 0; i < s.length; i++) s[i] = (short) (20000 * Math.sin(2 * Math.PI * f * i / RATE));
			check("steady " + f + " Hz tone: no detection", !stream(GROUP, s));
		}
		Random r = new Random(8); double[] sp = new double[RATE * 300]; double ph = 0, f = 500;
		for (int i = 0; i < sp.length; i++) { if (i % (400 + r.nextInt(800)) == 0) f = 120 + r.nextDouble() * 2900; ph += 2 * Math.PI * f / RATE; sp[i] = 0.4 * Math.sin(ph) * (0.5 + 0.5 * Math.sin(i / 900.0)) + 0.1 * r.nextGaussian(); }
		check("5 min of voice-like audio: no detection", !stream(GROUP, toS(sp)));
		// a periodic signal with the same 128 ms period that isn't ours (e.g. a buzzer or another app's beacon)
		Random q = new Random(9); double[] per = new double[1024]; for (int i = 0; i < 1024; i++) per[i] = q.nextGaussian() * 0.2;
		double[] rep = new double[RATE * 60]; for (int i = 0; i < rep.length; i++) rep[i] = per[i % 1024];
		check("60 s of a foreign signal repeating every 128 ms: no detection", !stream(GROUP, toS(rep)));
		if (Modem.load()) {
			short[] frames = new short[RATE * 60]; int pos = 0; byte[] p = new byte[256]; Random rr = new Random(10);
			while (pos < frames.length - 20000) { rr.nextBytes(p); Modem.encoderInit(p); short[] b = new short[1600]; boolean done = false;
				while (!done && pos + 1600 < frames.length) { done = Modem.encoderRead(b, 1600, 0.75f); System.arraycopy(b, 0, frames, pos, 1600); pos += 1600; } }
			check("60 s of back-to-back Ribbit frames: no detection", !stream(GROUP, frames));
		}
		// speed on this machine (the phone is much slower; this is just to see the order of magnitude)
		WakeTone.Detector det = GROUP.detector(); short[] win = at(sig, 0); long t0 = System.nanoTime();
		for (int i = 0; i < 200; i++) { det.reset(); det.process(win, 0, WakeTone.WINDOW); det.evaluateNow(); }
		System.out.printf("     one window: %.2f ms here%n", (System.nanoTime() - t0) / 200 / 1e6);
		System.out.println(fails == 0 ? "WakeTone: all passed" : "WakeTone: " + fails + " FAILED");
		System.exit(fails == 0 ? 0 : 1);
	}
}
