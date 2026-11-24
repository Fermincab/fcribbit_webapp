import com.fcribbit.ribbit.*;
import java.util.Random;

/** Host test for WakeTone: detection with noise/level/clipping, no false alarms on noise, speech-like signals or Ribbit frames. */
public class WakeToneTest {
	static int fails = 0;
	static void check(String what, boolean ok) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

	static boolean detect(short[] s) { WakeTone.Detector d = new WakeTone.Detector(); return d.process(s, 0, s.length); }

	static short[] mix(short[] s, double gain, double noiseRms, long seed) {
		Random r = new Random(seed); short[] o = new short[s.length];
		for (int i = 0; i < s.length; i++) { double v = s[i] * gain + r.nextGaussian() * noiseRms * 32768; o[i] = (short) Math.max(-32768, Math.min(32767, v)); }
		return o;
	}

	public static void main(String[] a) {
		short[] tone = WakeTone.generate(1000, 0.75f);
		check("clean tone 1 s", detect(tone));
		check("tone at 5% level", detect(mix(tone, 0.05, 0, 1)));
		check("tone + noise (SNR ~6 dB)", detect(mix(tone, 0.3, 0.075, 2)));
		check("tone heavily clipped (x8)", detect(mix(tone, 8, 0, 3)));
		// only 0.6 s of the tone inside a 0.85 s window, like a check that wakes up near the tone's end
		short[] win = new short[6800]; System.arraycopy(WakeTone.generate(600, 0.75f), 0, win, 0, 4800);
		check("0.6 s of tone inside a check window", detect(mix(win, 1, 0.002, 4)));
		short[] shortTone = new short[6800]; System.arraycopy(WakeTone.generate(300, 0.75f), 0, shortTone, 0, 2400);
		check("0.3 s of tone is NOT enough", !detect(shortTone));
		check("white noise 60 s: no false detection", !detect(mix(new short[480000], 0, 0.2, 5)));
		// a single steady tone (e.g. a carrier whistle, 1750 Hz call tone, 1000 Hz test tone) must not trigger
		for (double f : new double[] { 1000, 1750, 1800 }) {
			short[] s = new short[80000]; for (int i = 0; i < s.length; i++) s[i] = (short) (20000 * Math.sin(2 * Math.PI * f * i / 8000));
			check("steady " + (int) f + " Hz tone: no detection", !detect(s));
		}
		// speech-like: random tones with random switching between 300..3000 Hz
		Random r = new Random(6); short[] sp = new short[8000 * 60]; double ph = 0, f = 500;
		for (int i = 0; i < sp.length; i++) { if (i % (400 + r.nextInt(800)) == 0) f = 300 + r.nextDouble() * 2700; ph += 2 * Math.PI * f / 8000; sp[i] = (short) (15000 * Math.sin(ph) * (0.5 + 0.5 * Math.sin(i / 900.0))); }
		check("60 s of speech-like tone hopping: no detection", !detect(mix(sp, 1, 0.01, 7)));
		// Ribbit OFDM frames
		if (Modem.load()) {
			short[] frames = new short[8000 * 30]; int pos = 0; byte[] p = new byte[256]; Random rr = new Random(8);
			while (pos < frames.length - 20000) { rr.nextBytes(p); Modem.encoderInit(p); short[] b = new short[1600]; boolean done = false;
				while (!done && pos + 1600 < frames.length) { done = Modem.encoderRead(b, 1600, 0.75f); System.arraycopy(b, 0, frames, pos, 1600); pos += 1600; } }
			check("30 s of back-to-back Ribbit frames: no detection", !detect(frames));
		}
		System.out.println(fails == 0 ? "WakeTone: all passed" : "WakeTone: " + fails + " FAILED");
		System.exit(fails == 0 ? 0 : 1);
	}
}
