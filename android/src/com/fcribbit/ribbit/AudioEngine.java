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
 * 8 kHz audio for the modem: AudioRecord feeds the decoder, AudioTrack plays
 * the encoder's output. Like the web app, the decoder is fed silence while we
 * transmit so we don't decode ourselves.
 */
public final class AudioEngine {
	public interface Listener {
		void onPayload(byte[] payload, int result);

		void onLevel(int percent);

		void onRxError();

		void onTxDone();
	}

	private static final int RATE = Modem.SAMPLE_RATE;
	private static final int READ_SAMPLES = 480; // 60 ms
	private static final int MAX_TX_SAMPLES = RATE * 6;

	private final Handler main = new Handler(Looper.getMainLooper());
	private final Listener listener;
	private volatile boolean running;
	private volatile boolean muted;
	private volatile boolean transmitting;
	private Thread rxThread;
	private int micSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;

	public AudioEngine(Listener listener) {
		this.listener = listener;
	}

	public void setMicSource(int source) {
		micSource = source;
	}

	public boolean isTransmitting() {
		return transmitting;
	}

	public synchronized void start() {
		if (running)
			return;
		running = true;
		rxThread = new Thread(new Runnable() {
			public void run() {
				receiveLoop();
			}
		}, "ribbit-rx");
		rxThread.start();
	}

	public synchronized void stop() {
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

	private AudioRecord openRecorder(int minBuf) {
		int[] sources = micSource == MediaRecorder.AudioSource.MIC
				? new int[] { MediaRecorder.AudioSource.MIC }
				: new int[] { micSource, MediaRecorder.AudioSource.MIC };
		int bufBytes = Math.max(minBuf, RATE * 2); // 1 s of 16-bit mono
		for (int source : sources) {
			AudioRecord r = null;
			try {
				r = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
						AudioFormat.ENCODING_PCM_16BIT, bufBytes);
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

	private void receiveLoop() {
		Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
		int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
		AudioRecord rec = minBuf > 0 ? openRecorder(minBuf) : null;
		if (rec == null) {
			running = false;
			main.post(new Runnable() {
				public void run() {
					listener.onRxError();
				}
			});
			return;
		}
		short[] pcm = new short[READ_SAMPLES];
		byte[] payload = new byte[Modem.PAYLOAD_BYTES];
		long lastLevel = 0;
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
				final int result = Modem.decoderFeed(pcm, n, payload);
				if (result != Modem.NOTHING) {
					final byte[] copy = payload.clone();
					main.post(new Runnable() {
						public void run() {
							listener.onPayload(copy, result);
						}
					});
				}
				long now = System.currentTimeMillis();
				if (now - lastLevel > 120) {
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
			main.post(new Runnable() {
				public void run() {
					listener.onRxError();
				}
			});
		} finally {
			try {
				rec.stop();
			} catch (Exception e) {
				// ignore
			}
			rec.release();
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

	/** Transmit one 256 byte payload. Returns false if a transmission is already running. */
	public synchronized boolean transmit(final byte[] payload, final float gain) {
		if (transmitting)
			return false;
		transmitting = true;
		muted = true;
		new Thread(new Runnable() {
			public void run() {
				Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
				try {
					play(render(payload, gain));
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

	/** Runs the encoder: 100 ms lead-in silence + frame + 200 ms of silence. */
	private static short[] render(byte[] payload, float gain) {
		short[] buf = new short[MAX_TX_SAMPLES];
		int lead = RATE / 10;
		int pos = lead;
		short[] block = new short[1600];
		Modem.encoderInit(payload);
		boolean done = false;
		while (!done && pos < buf.length - RATE / 5) {
			int n = Math.min(block.length, buf.length - RATE / 5 - pos);
			done = Modem.encoderRead(block, n, gain);
			System.arraycopy(block, 0, buf, pos, n);
			pos += n;
		}
		int total = pos + RATE / 5;
		short[] out = new short[total];
		System.arraycopy(buf, 0, out, 0, total);
		return out;
	}

	private static void play(short[] samples) {
		AudioTrack track = null;
		try {
			int bytes = samples.length * 2;
			int minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
			track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
					AudioFormat.ENCODING_PCM_16BIT, Math.max(bytes, minBuf), AudioTrack.MODE_STATIC);
			track.write(samples, 0, samples.length);
			if (track.getState() != AudioTrack.STATE_INITIALIZED)
				return;
			track.play();
			long deadline = System.currentTimeMillis() + samples.length * 1000L / RATE + 2000;
			while (System.currentTimeMillis() < deadline) {
				int head = track.getPlaybackHeadPosition();
				if (head >= samples.length)
					break;
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					break;
				}
			}
			track.stop();
		} catch (Exception e) {
			// nothing sensible to do: the UI goes back to listening
		} finally {
			if (track != null)
				track.release();
		}
	}
}
