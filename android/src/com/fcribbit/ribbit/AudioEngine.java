package com.fcribbit.ribbit;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/**
 * 8 kHz audio for the modem.
 *
 * - continuous listening: AudioRecord feeds the decoder and the wake tone detector
 * - checkOnce(): a short listen used by the intermittent (sleep) mode, wake tone only
 * - transmit(): optional wake tone, then the Ribbit frame, through AudioTrack
 *
 * Like the web app, the decoder gets silence while we transmit so we don't decode ourselves.
 */
public final class AudioEngine {
	public interface Listener {
		void onPayload(byte[] payload, int result);

		void onWakeTone();

		void onLevel(int percent);

		void onRxError();

		void onTxDone();
	}

	private static final int RATE = Modem.SAMPLE_RATE;
	private static final int READ_SAMPLES = 400; // 50 ms
	private static final int SETTLE_MS = 150;    // ignore the first samples after the mic powers up

	private final Handler main = new Handler(Looper.getMainLooper());
	private final Listener listener;
	private volatile boolean running;
	private volatile boolean muted;
	private volatile boolean transmitting;
	private volatile boolean levelWanted = true;
	private Thread rxThread;
	private int micSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;
	private final Object micLock = new Object();

	public AudioEngine(Listener listener) {
		this.listener = listener;
	}

	public void setMicSource(int source) {
		micSource = source;
	}

	public void setLevelWanted(boolean wanted) {
		levelWanted = wanted;
	}

	public boolean isTransmitting() {
		return transmitting;
	}

	public synchronized boolean isListening() {
		return running;
	}

	public synchronized void startListening() {
		if (running)
			return;
		running = true;
		rxThread = new Thread(new Runnable() {
			public void run() {
				synchronized (micLock) {
					receiveLoop();
				}
			}
		}, "ribbit-rx");
		rxThread.start();
	}

	public synchronized void stopListening() {
		running = false;
		if (rxThread != null) {
			try {
				rxThread.join(1500);
			} catch (InterruptedException e) {
				// ignore
			}
			rxThread = null;
		}
	}

	private AudioRecord openRecorder() {
		int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
		if (minBuf <= 0)
			return null;
		int[] sources = micSource == MediaRecorder.AudioSource.MIC
				? new int[] { MediaRecorder.AudioSource.MIC }
				: new int[] { micSource, MediaRecorder.AudioSource.MIC };
		int bufBytes = Math.max(minBuf, RATE * 2); // 1 s of 16-bit mono
		for (int source : sources) {
			AudioRecord r = null;
			try {
				r = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
				if (r.getState() == AudioRecord.STATE_INITIALIZED)
					return r;
			} catch (Exception e) {
				// try the next source
			}
			if (r != null)
				r.release();
		}
		return null;
	}

	private void postRxError() {
		main.post(new Runnable() {
			public void run() {
				listener.onRxError();
			}
		});
	}

	private void receiveLoop() {
		Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
		AudioRecord rec = openRecorder();
		if (rec == null) {
			running = false;
			postRxError();
			return;
		}
		short[] pcm = new short[READ_SAMPLES];
		byte[] payload = new byte[Modem.PAYLOAD_BYTES];
		WakeTone.Detector detector = new WakeTone.Detector();
		long lastLevel = 0, lastTone = 0;
		try {
			rec.startRecording();
			while (running) {
				int n = rec.read(pcm, 0, pcm.length);
				if (n <= 0) {
					try {
						Thread.sleep(20);
					} catch (InterruptedException e) {
						// ignore
					}
					continue;
				}
				if (muted) {
					for (int i = 0; i < n; ++i)
						pcm[i] = 0;
				}
				long now = System.currentTimeMillis();
				if (detector.process(pcm, 0, n) && now - lastTone > 2000) {
					lastTone = now;
					main.post(new Runnable() {
						public void run() {
							listener.onWakeTone();
						}
					});
				}
				final int result = Modem.decoderFeed(pcm, n, payload);
				if (result != Modem.NOTHING) {
					final byte[] copy = payload.clone();
					main.post(new Runnable() {
						public void run() {
							listener.onPayload(copy, result);
						}
					});
				}
				if (levelWanted && now - lastLevel > 120) {
					lastLevel = now;
					final int level = levelPercent(pcm, n);
					main.post(new Runnable() {
						public void run() {
							listener.onLevel(level);
						}
					});
				}
			}
		} catch (Exception e) {
			postRxError();
		} finally {
			try {
				rec.stop();
			} catch (Exception e) {
				// ignore
			}
			rec.release();
		}
	}

	/**
	 * Intermittent mode: listen for windowMs (after letting the mic settle) and say
	 * whether the wake tone is on air. Blocking: call from a worker thread, never
	 * while continuous listening is running.
	 */
	public boolean checkOnce(int windowMs) {
		synchronized (micLock) {
			AudioRecord rec = openRecorder();
			if (rec == null)
				return false;
			WakeTone.Detector detector = new WakeTone.Detector();
			short[] pcm = new short[READ_SAMPLES];
			boolean heard = false;
			try {
				rec.startRecording();
				int skip = RATE * SETTLE_MS / 1000, want = RATE * windowMs / 1000, got = 0;
				while (got < skip + want) {
					int n = rec.read(pcm, 0, pcm.length);
					if (n <= 0)
						break;
					if (got >= skip && !transmitting && detector.process(pcm, 0, n)) {
						heard = true;
						break;
					}
					got += n;
				}
			} catch (Exception e) {
				// treat as nothing heard
			} finally {
				try {
					rec.stop();
				} catch (Exception e) {
					// ignore
				}
				rec.release();
			}
			return heard;
		}
	}

	/** RMS level mapped from -60..0 dBFS to 0..100 */
	private static int levelPercent(short[] pcm, int n) {
		double sum = 0;
		for (int i = 0; i < n; ++i)
			sum += (double) pcm[i] * pcm[i];
		double rms = Math.sqrt(sum / n) / 32768.0;
		if (rms <= 0)
			return 0;
		double db = 20 * Math.log10(rms);
		int p = (int) ((db + 60) * 100 / 60);
		return p < 0 ? 0 : (p > 100 ? 100 : p);
	}

	/**
	 * Transmit one 256 byte payload, preceded by toneMs of wake tone when toneMs > 0.
	 * Returns false if a transmission is already running.
	 */
	public synchronized boolean transmit(final byte[] payload, final float gain, final int toneMs) {
		if (transmitting)
			return false;
		transmitting = true;
		muted = true;
		new Thread(new Runnable() {
			public void run() {
				Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
				try {
					play(render(payload, gain, toneMs));
				} finally {
					// let the speaker's tail die out before listening again
					try {
						Thread.sleep(300);
					} catch (InterruptedException e) {
						// ignore
					}
					muted = false;
					transmitting = false;
					main.post(new Runnable() {
						public void run() {
							listener.onTxDone();
						}
					});
				}
			}
		}, "ribbit-tx").start();
		return true;
	}

	/** 100 ms silence + [wake tone + 300 ms] + frame + 200 ms silence */
	private static short[] render(byte[] payload, float gain, int toneMs) {
		short[] tone = toneMs > 0 ? WakeTone.generate(toneMs, gain) : new short[0];
		int gap = toneMs > 0 ? RATE * 3 / 10 : 0;
		int lead = RATE / 10, tail = RATE / 5;
		short[] frame = new short[RATE * 4];
		int len = 0;
		short[] block = new short[1600];
		Modem.encoderInit(payload);
		boolean done = false;
		while (!done && len + block.length <= frame.length) {
			done = Modem.encoderRead(block, block.length, gain);
			System.arraycopy(block, 0, frame, len, block.length);
			len += block.length;
		}
		short[] out = new short[lead + tone.length + gap + len + tail];
		System.arraycopy(tone, 0, out, lead, tone.length);
		System.arraycopy(frame, 0, out, lead + tone.length + gap, len);
		return out;
	}

	private static void play(short[] samples) {
		AudioTrack track = null;
		try {
			int minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
			track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
					AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, RATE), AudioTrack.MODE_STREAM);
			if (track.getState() != AudioTrack.STATE_INITIALIZED)
				return;
			track.play();
			for (int pos = 0; pos < samples.length;) {
				int n = track.write(samples, pos, Math.min(1600, samples.length - pos));
				if (n <= 0)
					break;
				pos += n;
			}
			long deadline = System.currentTimeMillis() + 3000;
			while (System.currentTimeMillis() < deadline && track.getPlaybackHeadPosition() < samples.length) {
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					break;
				}
			}
			track.stop();
		} catch (Exception e) {
			// nothing sensible to do: the app goes back to listening
		} finally {
			if (track != null)
				track.release();
		}
	}
}
