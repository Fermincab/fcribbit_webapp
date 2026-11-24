package com.fcribbit.ribbit;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;

/**
 * Plays a phone ring on the loudspeaker while the radio cable is plugged into the
 * jack: anything reaching the jack would key the radio's VOX.
 *
 * It turns the speakerphone on and plays on the voice call stream, like a call on
 * hands-free; Android then routes that stream to the loudspeaker even with something
 * plugged in. Ribbit's own audio uses the music stream, which keeps going to the
 * jack, so transmissions still work while the alarm rings. (Tested on the Galaxy Y
 * Pro; forcing the media stream through the hidden AudioSystem API did not work.)
 */
public final class AlarmPlayer {
	private static final int STREAM = AudioManager.STREAM_VOICE_CALL;

	private final Context context;
	private final AudioManager am;
	private MediaPlayer player;
	private AudioTrack track;
	private boolean active;
	private int savedVolume;
	private boolean savedSpeaker;

	public AlarmPlayer(Context context) {
		this.context = context.getApplicationContext();
		am = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
	}

	public synchronized void start() {
		stop();
		active = true;
		savedSpeaker = am.isSpeakerphoneOn();
		am.setSpeakerphoneOn(true);
		savedVolume = am.getStreamVolume(STREAM);
		am.setStreamVolume(STREAM, am.getStreamMaxVolume(STREAM), 0);
		if (!playRingtone())
			playGenerated();
	}

	public synchronized void stop() {
		if (player != null) {
			try {
				player.stop();
			} catch (Exception e) {
				// ignore
			}
			player.release();
			player = null;
		}
		if (track != null) {
			try {
				track.stop();
			} catch (Exception e) {
				// ignore
			}
			track.release();
			track = null;
		}
		if (!active)
			return;
		active = false;
		try {
			am.setStreamVolume(STREAM, savedVolume, 0);
		} catch (Exception e) {
			// ignore
		}
		am.setSpeakerphoneOn(savedSpeaker);
	}

	private boolean playRingtone() {
		Uri uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE);
		if (uri == null)
			uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
		if (uri == null)
			return false;
		MediaPlayer mp = new MediaPlayer();
		try {
			mp.setDataSource(context, uri);
			mp.setAudioStreamType(STREAM);
			mp.setLooping(true);
			mp.prepare();
			mp.start();
			player = mp;
			return true;
		} catch (Exception e) {
			mp.release();
			return false;
		}
	}

	/** fallback when there is no ringtone: classic ring, 440+480 Hz, 2 s on / 4 s off */
	private boolean playGenerated() {
		int rate = 8000, n = rate * 6;
		short[] pcm = new short[n];
		for (int i = 0; i < rate * 2; ++i) {
			double t = i / (double) rate;
			pcm[i] = (short) (12000 * (Math.sin(2 * Math.PI * 440 * t) + Math.sin(2 * Math.PI * 480 * t)));
		}
		try {
			AudioTrack t = new AudioTrack(STREAM, rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
					n * 2, AudioTrack.MODE_STATIC);
			t.write(pcm, 0, n);
			t.setLoopPoints(0, n, -1);
			t.play();
			track = t;
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
