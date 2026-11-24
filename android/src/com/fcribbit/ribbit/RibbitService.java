package com.fcribbit.ribbit;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps Ribbit listening with the app closed and the screen off.
 *
 * Listening modes (setting "listenMode"):
 * - intermittent: sleeps; every few seconds AlarmManager wakes the phone, it listens for
 *   about a second for the wake tone and goes back to sleep. Hearing the tone, or any
 *   session activity, switches to continuous listening until the session ends.
 * - continuous: always listening (for phones on a charger).
 * - foreground: only while the app is on screen (the original behaviour).
 * While the app is on screen it always listens continuously.
 */
public class RibbitService extends Service implements AudioEngine.Listener, SessionLogic.Actions {
	public static final String ACTION_START = "com.fcribbit.ribbit.START";
	public static final String ACTION_CHECK = "com.fcribbit.ribbit.CHECK";
	/** the alarm switched the speakerphone on or off: the microphone route may have changed */
	public static final String ACTION_AUDIO_ROUTE = "com.fcribbit.ribbit.AUDIO_ROUTE";

	public static final String MODE_INTERMITTENT = "intermittent";
	public static final String MODE_CONTINUOUS = "continuous";
	public static final String MODE_FOREGROUND = "foreground";

	private static final int NOTE_STATUS = 1;
	private static final int NOTE_MESSAGE = 2;
	/** the wake signal lasts the check interval plus this, so a sleeping phone can't miss it */
	private static final int WAKE_MARGIN_MS = 2500;
	private static final int MAX_MESSAGES = 500;

	public interface UiListener {
		void onChanged();

		void onLevel(int percent);

		void onToast(String text);
	}

	public final class LocalBinder extends Binder {
		public RibbitService service() {
			return RibbitService.this;
		}
	}

	private final IBinder binder = new LocalBinder();
	private final Handler handler = new Handler();
	private final ExecutorService crypto = Executors.newSingleThreadExecutor();
	private final List<ChatMessage> messages = new ArrayList<ChatMessage>();

	private SharedPreferences prefs;
	private MessageStore store;
	private AudioEngine engine;
	private SessionLogic logic;
	private PowerManager.WakeLock wakeLock;
	private NotificationManager notes;
	private AlarmManager alarms;
	private PendingIntent checkIntent;

	private boolean modemOk;
	private boolean uiVisible;
	private boolean bound;
	private boolean checking;
	private boolean ticking;
	private boolean foregroundShown;
	private UiListener ui;
	private String lastNote = "";
	private long lastBatterySample;

	// ------------------------------------------------------------ lifecycle

	@Override
	public void onCreate() {
		super.onCreate();
		PreferenceManager.setDefaultValues(this, R.xml.settings, false);
		prefs = PreferenceManager.getDefaultSharedPreferences(this);
		store = new MessageStore(this);
		notes = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
		checkIntent = PendingIntent.getBroadcast(this, 0,
				new Intent(this, WakeReceiver.class).setAction(ACTION_CHECK), 0);
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ribbit-listen");
		wakeLock.setReferenceCounted(false);
		modemOk = Modem.load();
		engine = new AudioEngine(this);
		logic = new SessionLogic(new SessionLogic.Clock() {
			public long now() {
				return SystemClock.elapsedRealtime();
			}
		}, this, new SessionLogic.HeaderSource() {
			public String header() {
				return stationHeader();
			}
		});
		if (prefs.getBoolean("saveMessages", true))
			messages.addAll(store.loadRecent());
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		String action = intent == null ? ACTION_START : intent.getAction();
		if (ACTION_CHECK.equals(action)) {
			wakeLock.acquire();
			WakeReceiver.releaseLock();
			runCheck();
		} else if (ACTION_AUDIO_ROUTE.equals(action)) {
			DebugLog.log("audio route changed by the alarm (listening=" + engine.isListening() + ")");
			handler.postDelayed(new Runnable() {
				public void run() {
					if (engine.isListening())
						engine.reopen();
					update();
				}
			}, 500);
		} else {
			update();
		}
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		bound = true;
		return binder;
	}

	@Override
	public boolean onUnbind(Intent intent) {
		bound = false;
		ui = null;
		uiVisible = false;
		update();
		return false;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		alarms.cancel(checkIntent);
		engine.stopListening();
		if (wakeLock.isHeld())
			wakeLock.release();
		crypto.shutdown();
		store.close();
		notes.cancel(NOTE_STATUS);
	}

	// --------------------------------------------------------- for the UI

	public void setUi(UiListener listener, boolean visible) {
		ui = listener;
		uiVisible = visible;
		engine.setLevelWanted(visible);
		if (visible)
			notes.cancel(NOTE_MESSAGE);
		update();
	}

	public boolean modemOk() {
		return modemOk;
	}

	public List<ChatMessage> messages() {
		return messages;
	}

	public SessionLogic session() {
		return logic;
	}

	public boolean transmitting() {
		return engine.isTransmitting();
	}

	public void sendText(String header, String body, String shown, boolean encrypted) {
		applySettings();
		logic.send(Payload.pack(header, body),
				new ChatMessage(System.currentTimeMillis(), ChatMessage.OUT, header, shown, encrypted));
		changed();
	}

	public void sendAlarm() {
		applySettings();
		String header = stationHeader();
		logic.send(Payload.pack(header, SessionLogic.CONTROL + SessionLogic.ALARM),
				new ChatMessage(System.currentTimeMillis(), ChatMessage.ALERT, header, getString(R.string.alarm_sent), false));
		changed();
	}

	public void clearMessages() {
		store.clear();
		messages.clear();
		addMessage(new ChatMessage(System.currentTimeMillis(), ChatMessage.ALERT, "", getString(R.string.messages_cleared), false), false);
	}

	// ------------------------------------------------------- listening mode

	private String mode() {
		return prefs.getString("listenMode", MODE_INTERMITTENT);
	}

	private int intervalMs() {
		return parseInt(prefs.getString("checkInterval", "7"), 7) * 1000;
	}

	private void applySettings() {
		logic.sessionMs = parseInt(prefs.getString("sessionMinutes", "5"), 5) * 60000L;
		logic.toneMs = intervalMs() + WAKE_MARGIN_MS; // 7 s -> 9.5 s
		engine.setGroupCode(prefs.getString("groupCode", WakeTone.DEFAULT_CODE));
		engine.setMicSource(parseInt(prefs.getString("micSource", "6"), 6));
	}

	/** decide between continuous listening, intermittent checks or nothing */
	private void update() {
		if (!modemOk) {
			stopSelfIfIdle();
			return;
		}
		applySettings();
		String mode = mode();
		boolean wasListening = engine.isListening();
		boolean background = !MODE_FOREGROUND.equals(mode);
		boolean continuous = uiVisible || logic.busy() || MODE_CONTINUOUS.equals(mode);
		if (continuous) {
			alarms.cancel(checkIntent);
			if (!checking)
				engine.startListening();
			wakeLock.acquire();
			handler.removeCallbacks(batteryTick);
			handler.postDelayed(batteryTick, 60000);
		} else {
			engine.stopListening();
			if (background && !checking) {
				scheduleCheck();
			} else if (!background) {
				alarms.cancel(checkIntent);
			}
			if (!checking && wakeLock.isHeld())
				wakeLock.release();
		}
		if (logic.busy() && !ticking) {
			ticking = true;
			handler.post(ticker);
		}
		if (wasListening != engine.isListening())
			DebugLog.log("listening " + (engine.isListening() ? "continuous" : "stopped (" + (background ? "sleep cycle" : "app closed") + ")")
					+ ", session " + logic.state());
		updateStatusNotification(background);
		if (!background && !uiVisible && !logic.busy())
			stopSelfIfIdle();
		changed();
	}

	private void stopSelfIfIdle() {
		if (!bound && !logic.busy()) {
			engine.stopListening();
			alarms.cancel(checkIntent);
			if (wakeLock.isHeld())
				wakeLock.release();
			stopSelf();
		}
	}

	private void scheduleCheck() {
		alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + intervalMs(), checkIntent);
	}

	/** one intermittent check: listen about a second for the wake tone */
	private void runCheck() {
		if (checking || uiVisible || logic.busy() || !modemOk || !MODE_INTERMITTENT.equals(mode())) {
			update();
			return;
		}
		checking = true;
		new Thread(new Runnable() {
			public void run() {
				final boolean heard = engine.checkOnce();
				handler.post(new Runnable() {
					public void run() {
						checking = false;
						sampleBattery();
						if (heard)
							logic.onToneHeard(); // -> stateChanged() -> continuous listening
						else
							update();
					}
				});
			}
		}, "ribbit-check").start();
	}

	/** battery samples while listening continuously without a session */
	private final Runnable batteryTick = new Runnable() {
		public void run() {
			if (engine.isListening()) {
				sampleBattery();
				handler.postDelayed(this, 60000);
			}
		}
	};

	private final Runnable ticker = new Runnable() {
		public void run() {
			if (!logic.busy()) {
				ticking = false;
				update();
				return;
			}
			logic.tick();
			sampleBattery();
			changed();
			handler.postDelayed(this, 500);
		}
	};

	// ------------------------------------------------------ AudioEngine

	public void onPayload(byte[] payload, int result) {
		if (result < 0) {
			DebugLog.log("  -> ignored (CRC failed)");
			toast(getString(R.string.bad_frame));
			return;
		}
		Payload.Parsed p = Payload.parse(payload);
		DebugLog.log("  from '" + DebugLog.preview(p.sender) + "': '" + DebugLog.preview(p.message) + "'");
		if (p.sender.length() > 0 && p.message != null && p.message.length() > 0) {
			SessionLogic.State before = logic.state();
			int count = messages.size();
			logic.onFrame(p.sender, p.message);
			DebugLog.log("  session " + before + " -> " + logic.state() + (messages.size() > count ? ", shown in chat" : ", not shown here"));
		} else
			addMessage(new ChatMessage(System.currentTimeMillis(), ChatMessage.IN, getString(R.string.unknown), p.text, false), true);
	}

	public void onWakeTone() {
		SessionLogic.State before = logic.state();
		logic.onToneHeard();
		if (before != logic.state())
			DebugLog.log("  session " + before + " -> " + logic.state());
	}

	public void onLevel(int percent) {
		if (ui != null)
			ui.onLevel(engine.isTransmitting() ? 0 : percent);
	}

	public void onRxError() {
		toast(getString(R.string.mic_error));
	}

	public void onTxDone() {
		logic.onTxDone();
		changed();
	}

	// ------------------------------------------------------ SessionLogic

	public void transmit(SessionLogic.Outgoing o, int toneMs) {
		float gain = parseFloat(prefs.getString("txLevel", "0.75"), 0.75f);
		if (!engine.isListening())
			engine.startListening(); // so the decoder is muted and we hear the reply right after
		wakeLock.acquire();
		if (!engine.transmit(o.payload, gain, toneMs))
			handler.postDelayed(new Runnable() {
				public void run() {
					logic.onTxDone();
				}
			}, 500);
		changed();
	}

	public void deliver(final ChatMessage m) {
		if (m.type == ChatMessage.IN && RibbitCrypto.isEncrypted(m.body)) {
			final String password = prefs.getString("encryptionPassword", "");
			if (password.length() == 0) {
				received(new ChatMessage(m.time, m.type, m.sender, getString(R.string.enc_no_password), true));
				return;
			}
			crypto.execute(new Runnable() {
				public void run() {
					final String text = RibbitCrypto.decrypt(m.body, password, m.sender);
					handler.post(new Runnable() {
						public void run() {
							received(new ChatMessage(m.time, m.type, m.sender,
									text != null ? text : getString(R.string.enc_wrong_password), true));
						}
					});
				}
			});
			return;
		}
		if (m.type == ChatMessage.IN)
			received(m);
		else
			addMessage(m, true);
	}

	private void received(ChatMessage m) {
		addMessage(m, true);
		if (!uiVisible)
			notifyMessage(Payload.describeSender(m.sender), m.body);
	}

	public void alarm(String sender) {
		DebugLog.log("alarm received -> opening alarm screen");
		String who = Payload.describeSender(sender);
		addMessage(new ChatMessage(System.currentTimeMillis(), ChatMessage.ALERT, sender,
				getString(R.string.alarm_received, who), false), true);
		startActivity(new Intent(this, AlarmActivity.class)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				.putExtra(AlarmActivity.EXTRA_SENDER, sender));
	}

	public void event(SessionLogic.Event e, int detail) {
		DebugLog.log("session event " + e + (detail != 0 ? " " + detail : "") + " (state " + logic.state() + ")");
		switch (e) {
			case OPENED:
				alert(getString(R.string.session_opened));
				break;
			case CLOSED:
				alert(getString(R.string.session_closed));
				break;
			case RETRY:
				alert(getString(R.string.session_retry, detail, SessionLogic.MAX_ATTEMPTS));
				break;
			case NO_RESPONSE:
				alert(getString(R.string.session_no_response));
				break;
			case QUEUED:
				toast(getString(R.string.session_queued));
				break;
			case CLOSING_SOON:
				toast(getString(R.string.session_closing, detail));
				break;
			default:
		}
	}

	public void stateChanged() {
		update();
	}

	public boolean canTransmit() {
		return true; // the alarm rings on the call stream; Ribbit's audio still goes to the jack
	}

	// ------------------------------------------------------------- misc

	private String stationHeader() {
		return Payload.header(prefs.getString("name", ""), prefs.getString("callsign", ""),
				prefs.getString("gridsquare", ""), prefs.getString("phone", ""));
	}

	private void alert(String text) {
		addMessage(new ChatMessage(System.currentTimeMillis(), ChatMessage.ALERT, "", text, false), true);
	}

	private void addMessage(ChatMessage m, boolean save) {
		if (save && prefs.getBoolean("saveMessages", true))
			store.add(m);
		messages.add(m);
		while (messages.size() > MAX_MESSAGES)
			messages.remove(0);
		changed();
	}

	private void changed() {
		if (ui != null)
			ui.onChanged();
	}

	private void toast(String s) {
		if (ui != null && uiVisible)
			ui.onToast(s);
	}

	private PendingIntent openApp() {
		return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
				.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
	}

	@SuppressWarnings("deprecation")
	private void updateStatusNotification(boolean background) {
		if (!background) {
			if (foregroundShown) {
				stopForeground(true);
				foregroundShown = false;
				lastNote = "";
			}
			return;
		}
		String text;
		switch (logic.state()) {
			case SESSION:
				text = getString(R.string.note_session);
				break;
			case OPENING:
			case WOKEN:
				text = getString(R.string.note_opening);
				break;
			default:
				text = MODE_CONTINUOUS.equals(mode()) || uiVisible ? getString(R.string.note_listening)
						: getString(R.string.note_sleeping, intervalMs() / 1000);
		}
		if (foregroundShown && text.equals(lastNote))
			return;
		lastNote = text;
		Notification n = new Notification(R.drawable.ic_launcher, null, 0);
		n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
		n.setLatestEventInfo(this, getString(R.string.app_name), text, openApp());
		if (!foregroundShown) {
			startForeground(NOTE_STATUS, n);
			foregroundShown = true;
		} else {
			notes.notify(NOTE_STATUS, n);
		}
	}

	/** vibration and light only: a sound would reach the jack and key the radio's VOX */
	@SuppressWarnings("deprecation")
	private void notifyMessage(String who, String body) {
		Notification n = new Notification(R.drawable.ic_launcher, who + ": " + body, System.currentTimeMillis());
		n.flags |= Notification.FLAG_AUTO_CANCEL | Notification.FLAG_SHOW_LIGHTS;
		n.defaults = Notification.DEFAULT_LIGHTS;
		n.vibrate = new long[] { 0, 300, 200, 300, 200, 600 };
		n.sound = null;
		n.setLatestEventInfo(this, who.length() > 0 ? who : getString(R.string.app_name), body, openApp());
		notes.notify(NOTE_MESSAGE, n);
	}

	/** battery drain while unplugged, for the settings screen */
	private void sampleBattery() {
		long now = System.currentTimeMillis();
		if (now - lastBatterySample < 60000)
			return;
		lastBatterySample = now;
		Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		if (b == null)
			return;
		int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
		int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
		boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
		if (level < 0 || scale <= 0)
			return;
		int pct = level * 100 / scale;
		SharedPreferences.Editor e = prefs.edit();
		if (plugged) {
			e.remove("battStartTime").remove("battStartLevel");
		} else if (!prefs.contains("battStartTime")) {
			e.putLong("battStartTime", now).putInt("battStartLevel", pct);
		}
		e.putLong("battLastTime", now).putInt("battLastLevel", pct).commit();
	}

	private static int parseInt(String s, int def) {
		try {
			return Integer.parseInt(s);
		} catch (Exception e) {
			return def;
		}
	}

	private static float parseFloat(String s, float def) {
		try {
			return Float.parseFloat(s);
		} catch (Exception e) {
			return def;
		}
	}
}
