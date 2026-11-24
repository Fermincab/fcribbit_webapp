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
 * - checkOnce(): a short listen used by the intermittent (sleep) mode, wake signal only
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
	private volatile int generation;
	private volatile AudioRecord current;
	private volatile long lastReadAt;
	private volatile boolean reopenWanted;
	private static final int DEAD_SILENCE_SAMPLES = RATE * 8; // 8 s of exact zeros: the input is dead

	public AudioEngine(Listener listener) {
		this.listener = listener;
	}

	/** a different source takes effect immediately (the microphone is reopened) */
	public void setMicSource(int source) {
		if (source == micSource)
			return;
		micSource = source;
		if (running) {
			DebugLog.log("mic source changed to " + source);
			reopen();
		}
	}

	private volatile WakeTone wake = new WakeTone(WakeTone.DEFAULT_CODE);
	private String groupCode = WakeTone.DEFAULT_CODE;

	/** the group code decides which wake signal we send and react to */
	public synchronized void setGroupCode(String code) {
		String c = code == null || code.trim().length() == 0 ? WakeTone.DEFAULT_CODE : code.trim();
		if (!c.equals(groupCode)) {
			groupCode = c;
			wake = new WakeTone(c);
			DebugLog.log("group code changed");
		}
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
		spawnReceiver();
		main.removeCallbacks(watchdog);
		main.postDelayed(watchdog, 1000);
	}

	private synchronized void spawnReceiver() {
		final int gen = ++generation;
		lastReadAt = System.currentTimeMillis();
		rxThread = new Thread(new Runnable() {
			public void run() {
				receiveLoop(gen);
			}
		}, "ribbit-rx");
		rxThread.start();
	}

	public synchronized void stopListening() {
		running = false;
		main.removeCallbacks(watchdog);
		AudioRecord r = current;
		if (r != null) {
			try {
				r.stop(); // unblocks a read() that hangs
			} catch (Exception e) {
				// ignore
			}
		}
		if (rxThread != null) {
			try {
				rxThread.join(1500);
			} catch (InterruptedException e) {
				// ignore
			}
			rxThread = null;
		}
	}

	/**
	 * Close and reopen the microphone. Used after the alarm, whose speakerphone
	 * switching can leave an open AudioRecord delivering nothing.
	 */
	public void reopen() {
		DebugLog.log("mic: reopen requested");
		reopenWanted = true;
		AudioRecord r = current;
		if (r != null) {
			try {
				r.stop();
			} catch (Exception e) {
				// ignore
			}
		}
	}

	/** restarts reception when the microphone stops delivering samples */
	private final Runnable watchdog = new Runnable() {
		public void run() {
			if (!running)
				return;
			long stale = System.currentTimeMillis() - lastReadAt;
			if (stale > 3000)
				DebugLog.log("mic watchdog: no samples for " + stale + " ms");
			if (stale > 6000) {
				// the reading thread is stuck for good: leave it behind and start a new one
				synchronized (AudioEngine.this) {
					if (running)
						spawnReceiver();
				}
			} else if (stale > 3000) {
				reopen();
			}
			main.postDelayed(this, 1000);
		}
	};

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
				if (r.getState() == AudioRecord.STATE_INITIALIZED) {
					DebugLog.log("mic opened, source=" + source);
					return r;
				}
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

	private void receiveLoop(int gen) {
		Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
		short[] pcm = new short[READ_SAMPLES];
		byte[] payload = new byte[Modem.PAYLOAD_BYTES];
		WakeTone signal = wake;
		WakeTone.Detector detector = signal.detector();
		long lastLevel = 0, lastTone = 0;
		int openFailures = 0;
		while (running && gen == generation) {
			AudioRecord rec = openRecorder();
			if (rec == null) {
				if (++openFailures == 10)
					postRxError();
				sleep(500);
				continue;
			}
			openFailures = 0;
			reopenWanted = false;
			current = rec;
			int errors = 0, zeros = 0;
			try {
				rec.startRecording();
				while (running && gen == generation && !reopenWanted) {
					int n = rec.read(pcm, 0, pcm.length);
					lastReadAt = System.currentTimeMillis();
					if (n <= 0) {
						if (++errors > 10) {
							DebugLog.log("mic: read errors (" + n + "), reopening");
							break; // reopen
						}
						sleep(20);
						continue;
					}
					errors = 0;
					// a live input always has some noise; long exact silence means a dead route
					boolean allZero = true;
					for (int i = 0; i < n && allZero; ++i)
						allZero = pcm[i] == 0;
					zeros = allZero && !transmitting ? zeros + n : 0;
					if (zeros > DEAD_SILENCE_SAMPLES) {
						DebugLog.log("mic: 8 s of digital silence, reopening");
						break; // reopen
					}
					if (muted) {
						for (int i = 0; i < n; ++i)
							pcm[i] = 0;
					}
					long now = lastReadAt;
					if (wake != signal) {
						signal = wake;
						detector = signal.detector();
					}
					if (detector.process(pcm, 0, n) && now - lastTone > 2000) {
						lastTone = now;
						DebugLog.log(String.format("wake signal heard (score %.2f)", detector.lastScore()));
						main.post(new Runnable() {
							public void run() {
								listener.onWakeTone();
							}
						});
					}
					final int result = Modem.decoderFeed(pcm, n, payload);
					if (result != Modem.NOTHING) {
						DebugLog.log(result < 0 ? "FRAME received, CRC FAILED (signal distorted or too weak)"
								: "frame decoded OK, corrected bits=" + result);
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
				// reopen below
			} finally {
				current = null;
				try {
					rec.stop();
				} catch (Exception e) {
					// ignore
				}
				rec.release();
			}
			if (running && gen == generation)
				sleep(200);
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			// ignore
		}
	}

	/**
	 * Intermittent mode: let the mic settle, listen for one detector window (512 ms) and
	 * say whether our group's wake signal is on air. Blocking: call from a worker thread,
	 * never while continuous listening is running.
	 */
	public boolean checkOnce() {
		synchronized (micLock) {
			AudioRecord rec = openRecorder();
			if (rec == null)
				return false;
			WakeTone.Detector detector = wake.detector();
			short[] pcm = new short[READ_SAMPLES];
			boolean heard = false;
			try {
				rec.startRecording();
				int skip = RATE * SETTLE_MS / 1000, got = 0;
				while (got < skip + WakeTone.WINDOW) {
					int want = got < skip ? skip - got : skip + WakeTone.WINDOW - got;
					int n = rec.read(pcm, 0, Math.min(pcm.length, want));
					if (n <= 0)
						break;
					if (got >= skip)
						detector.process(pcm, 0, n);
					got += n;
				}
				heard = !transmitting && detector.evaluateNow();
				if (heard)
					DebugLog.log(String.format("sleep check: wake signal heard (score %.2f)", detector.lastScore()));
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
		DebugLog.log("TX start" + (toneMs > 0 ? " with wake tone " + toneMs + " ms" : ""));
		new Thread(new Runnable() {
			public void run() {
				Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
				try {
					play(render(wake, payload, gain, toneMs));
				} finally {
					// let the speaker's tail die out before listening again
					try {
						Thread.sleep(300);
					} catch (InterruptedException e) {
						// ignore
					}
					muted = false;
					transmitting = false;
					DebugLog.log("TX done");
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
	private static short[] render(WakeTone wake, byte[] payload, float gain, int toneMs) {
		short[] tone = toneMs > 0 ? wake.generate(toneMs, gain) : new short[0];
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
