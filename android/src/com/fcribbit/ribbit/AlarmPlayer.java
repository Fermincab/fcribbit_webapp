package com.fcribbit.ribbit;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;

import java.lang.reflect.Method;

/**
 * Plays a phone ring while the radio cable is plugged into the jack. The goal is to
 * ring on the phone's loudspeaker only, because anything reaching the jack keys the
 * radio's VOX. Android 2.3 has no official way to do that, so there are several
 * methods to try on each phone:
 *
 * - SPEAKERPHONE: setSpeakerphoneOn(true) and play on the voice call stream
 * - IN_CALL: same, with the audio mode set to "in call" (some phones only route then)
 * - FORCE_MEDIA: hidden AudioSystem.setForceUse(FOR_MEDIA, FORCE_SPEAKER) and the music stream
 * - RING: the normal ringtone stream; Android plays it on speaker AND jack (reference)
 */
public final class AlarmPlayer {
	public static final String SPEAKERPHONE = "speakerphone";
	public static final String IN_CALL = "incall";
	public static final String FORCE_MEDIA = "forcemedia";
	public static final String RING = "ring";

	// android.media.AudioSystem constants (hidden API)
	private static final int FOR_MEDIA = 1;
	private static final int FORCE_NONE = 0;
	private static final int FORCE_SPEAKER = 1;

	private final Context context;
	private final AudioManager am;
	private MediaPlayer player;
	private AudioTrack track;
	private String method;
	private int stream;
	private int savedMode, savedVolume;
	private boolean savedSpeaker;
	private boolean forcedMedia;
	private String result = "";

	public AlarmPlayer(Context context) {
		this.context = context.getApplicationContext();
		am = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
	}

	/** what happened, for the screen: method, stream and whether routing calls were accepted */
	public String result() {
		return result;
	}

	public synchronized void start(String how) {
		stop();
		method = how;
		savedMode = am.getMode();
		savedSpeaker = am.isSpeakerphoneOn();
		StringBuilder r = new StringBuilder(how);
		if (IN_CALL.equals(how)) {
			try {
				am.setMode(AudioManager.MODE_IN_CALL);
				r.append(am.getMode() == AudioManager.MODE_IN_CALL ? " · mode=IN_CALL" : " · mode refused");
			} catch (Exception e) {
				r.append(" · mode error");
			}
		}
		if (SPEAKERPHONE.equals(how) || IN_CALL.equals(how)) {
			stream = AudioManager.STREAM_VOICE_CALL;
			am.setSpeakerphoneOn(true);
			r.append(am.isSpeakerphoneOn() ? " · speakerphone on" : " · speakerphone refused");
		} else if (FORCE_MEDIA.equals(how)) {
			stream = AudioManager.STREAM_MUSIC;
			forcedMedia = setForceUse(FOR_MEDIA, FORCE_SPEAKER);
			r.append(forcedMedia ? " · setForceUse ok" : " · setForceUse unavailable");
		} else {
			stream = AudioManager.STREAM_RING;
		}
		savedVolume = am.getStreamVolume(stream);
		am.setStreamVolume(stream, am.getStreamMaxVolume(stream), 0);
		r.append(am.isWiredHeadsetOn() ? " · jack: plugged" : " · jack: empty");
		if (playRingtone())
			r.append(" · ringtone");
		else if (playGenerated())
			r.append(" · generated ring");
		else
			r.append(" · NO SOUND");
		result = r.toString();
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
		if (method == null)
			return;
		try {
			am.setStreamVolume(stream, savedVolume, 0);
		} catch (Exception e) {
			// ignore
		}
		if (forcedMedia) {
			setForceUse(FOR_MEDIA, FORCE_NONE);
			forcedMedia = false;
		}
		am.setSpeakerphoneOn(savedSpeaker);
		if (am.getMode() != savedMode) {
			try {
				am.setMode(savedMode);
			} catch (Exception e) {
				// ignore
			}
		}
		method = null;
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
			mp.setAudioStreamType(stream);
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

	/** classic ring: 440+480 Hz, 2 s on / 4 s off, looped */
	private boolean playGenerated() {
		int rate = 8000, n = rate * 6;
		short[] pcm = new short[n];
		for (int i = 0; i < rate * 2; ++i) {
			double t = i / (double) rate;
			pcm[i] = (short) (12000 * (Math.sin(2 * Math.PI * 440 * t) + Math.sin(2 * Math.PI * 480 * t)));
		}
		try {
			AudioTrack t = new AudioTrack(stream, rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
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

	private static boolean setForceUse(int usage, int config) {
		try {
			Class<?> c = Class.forName("android.media.AudioSystem");
			Method m = c.getMethod("setForceUse", int.class, int.class);
			Object r = m.invoke(null, usage, config);
			return !(r instanceof Integer) || (Integer) r == 0;
		} catch (Throwable t) {
			return false;
		}
	}
}
