package com.fcribbit.ribbit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements AudioEngine.Listener {
	private static final int MENU_SETTINGS = 1;
	private static final int MENU_CLEAR = 2;

	private final Handler handler = new Handler();
	/** PBKDF2 runs here so neither the UI nor the audio threads stall */
	private final ExecutorService crypto = Executors.newSingleThreadExecutor();

	private SharedPreferences prefs;
	private MessageStore store;
	private MessageAdapter adapter;
	private AudioEngine engine;
	private PowerManager.WakeLock wakeLock;

	private ListView chat;
	private EditText input;
	private Button send;
	private TextView status;
	private TextView counter;
	private ProgressBar level;

	private boolean modemOk;
	private boolean encrypting;
	private boolean askedForSettings;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		setVolumeControlStream(AudioManager.STREAM_MUSIC);
		PreferenceManager.setDefaultValues(this, R.xml.settings, false);
		prefs = PreferenceManager.getDefaultSharedPreferences(this);
		store = new MessageStore(this);
		adapter = new MessageAdapter(this);
		engine = new AudioEngine(this);
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "ribbit");
		wakeLock.setReferenceCounted(false);

		chat = (ListView) findViewById(R.id.chat);
		input = (EditText) findViewById(R.id.message);
		send = (Button) findViewById(R.id.send);
		status = (TextView) findViewById(R.id.status);
		counter = (TextView) findViewById(R.id.counter);
		level = (ProgressBar) findViewById(R.id.level);
		chat.setAdapter(adapter);

		send.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				sendMessage();
			}
		});
		((ImageButton) findViewById(R.id.settings_button)).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				openSettings();
			}
		});
		// the keyboard's send key (or Enter on a hardware keyboard) sends, like the web app
		input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
			public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
				if (actionId == EditorInfo.IME_ACTION_SEND
						|| (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
					if (event == null || event.getAction() == KeyEvent.ACTION_DOWN)
						sendMessage();
					return true;
				}
				return false;
			}
		});
		input.addTextChangedListener(new TextWatcher() {
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			public void afterTextChanged(Editable s) {
				updateCounter();
			}
		});

		modemOk = Modem.load();
		if (!modemOk) {
			send.setEnabled(false);
			new AlertDialog.Builder(this).setMessage(R.string.native_error)
					.setPositiveButton(android.R.string.ok, null).show();
		}
		if (prefs.getBoolean("saveMessages", true))
			adapter.setAll(store.loadRecent());
	}

	@Override
	protected void onResume() {
		super.onResume();
		adapter.setHideSenders(prefs.getBoolean("hideSenders", false));
		updateCounter();
		if (modemOk) {
			engine.setMicSource(parseInt(prefs.getString("micSource", "6"), 6));
			engine.start();
			wakeLock.acquire();
			showListening();
		}
		// like the web app: open the settings when the station isn't set up yet
		if (!askedForSettings && prefs.getString("name", "").length() == 0) {
			askedForSettings = true;
			openSettings();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		engine.stop();
		if (wakeLock.isHeld())
			wakeLock.release();
		level.setProgress(0);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		crypto.shutdown();
		store.close();
	}

	// ------------------------------------------------------------------ menu

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		menu.add(0, MENU_SETTINGS, 0, R.string.settings).setIcon(android.R.drawable.ic_menu_preferences);
		menu.add(0, MENU_CLEAR, 1, R.string.clear_messages).setIcon(android.R.drawable.ic_menu_delete);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case MENU_SETTINGS:
				openSettings();
				return true;
			case MENU_CLEAR:
				new AlertDialog.Builder(this).setMessage(R.string.clear_messages_confirm)
						.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface d, int w) {
								store.clear();
								adapter.clear();
								addMessage(new ChatMessage(System.currentTimeMillis(), ChatMessage.ALERT, "",
										getString(R.string.messages_cleared), false), false);
							}
						}).setNegativeButton(R.string.no, null).show();
				return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void openSettings() {
		startActivity(new Intent(this, SettingsActivity.class));
	}

	// ------------------------------------------------------------- transmit

	private String header() {
		return Payload.header(prefs.getString("name", ""), prefs.getString("callsign", ""),
				prefs.getString("gridsquare", ""), prefs.getString("phone", ""));
	}

	private boolean encryptionOn() {
		return prefs.getBoolean("encryptionEnabled", false);
	}

	private void updateCounter() {
		String text = input.getText().toString();
		int room = Payload.room(header());
		int used = Payload.utf8(text).length;
		if (encryptionOn()) {
			int max = Math.max(RibbitCrypto.maxPlaintextBytes(room), 0);
			counter.setText(getString(R.string.enc_bytes_left, max - used));
		} else {
			counter.setText(getString(R.string.bytes_left, room - used));
		}
		counter.setTextColor(used > (encryptionOn() ? RibbitCrypto.maxPlaintextBytes(room) : room)
				? 0xFFFF6060 : 0xFF9AA59A);
	}

	private void sendMessage() {
		if (!modemOk)
			return;
		final String plaintext = input.getText().toString();
		if (plaintext.length() < 1)
			return;
		// don't send while transmitting or encrypting
		if (engine.isTransmitting() || encrypting)
			return;
		final String header = header();
		int room = Payload.room(header);
		if (!encryptionOn()) {
			if (Payload.utf8(plaintext).length > room) {
				toast(getString(R.string.too_long, Math.max(room, 0)));
				return;
			}
			transmit(header, plaintext, plaintext, false);
			return;
		}
		final String password = prefs.getString("encryptionPassword", "");
		if (password.length() == 0) {
			toast(getString(R.string.set_password));
			return;
		}
		int maxBytes = RibbitCrypto.maxPlaintextBytes(room);
		if (Payload.utf8(plaintext).length > maxBytes) {
			toast(getString(R.string.enc_too_long, Math.max(maxBytes, 0)));
			return;
		}
		encrypting = true;
		status.setText(R.string.encrypting);
		send.setEnabled(false);
		crypto.execute(new Runnable() {
			public void run() {
				final String body = RibbitCrypto.encrypt(plaintext, password, header);
				handler.post(new Runnable() {
					public void run() {
						encrypting = false;
						send.setEnabled(true);
						if (body == null) {
							toast(getString(R.string.encryption_failed));
							showListening();
							return;
						}
						transmit(header, body, plaintext, true);
					}
				});
			}
		});
	}

	private void transmit(String header, String body, String shown, boolean encrypted) {
		float gain = parseFloat(prefs.getString("txLevel", "0.75"), 0.75f);
		if (!engine.transmit(Payload.pack(header, body), gain))
			return;
		status.setText(R.string.transmitting);
		send.setEnabled(false);
		input.setText("");
		addMessage(new ChatMessage(System.currentTimeMillis(), ChatMessage.OUT, header, shown, encrypted), true);
	}

	// --------------------------------------------------------- AudioEngine

	public void onTxDone() {
		send.setEnabled(modemOk && !encrypting);
		showListening();
	}

	public void onLevel(int percent) {
		level.setProgress(engine.isTransmitting() ? 0 : percent);
	}

	public void onRxError() {
		status.setText(R.string.mic_error);
	}

	public void onPayload(byte[] payload, int result) {
		if (result < 0) {
			// the web app shows these as garbage; just note them in the status line
			status.setText(R.string.bad_frame);
			handler.postDelayed(new Runnable() {
				public void run() {
					if (!engine.isTransmitting() && !encrypting)
						showListening();
				}
			}, 3000);
			return;
		}
		final Payload.Parsed p = Payload.parse(payload);
		final long now = System.currentTimeMillis();
		if (p.sender.length() > 0 && RibbitCrypto.isEncrypted(p.message)) {
			final String password = prefs.getString("encryptionPassword", "");
			if (password.length() == 0) {
				addMessage(new ChatMessage(now, ChatMessage.IN, p.sender, getString(R.string.enc_no_password), true), true);
				return;
			}
			crypto.execute(new Runnable() {
				public void run() {
					final String text = RibbitCrypto.decrypt(p.message, password, p.sender);
					handler.post(new Runnable() {
						public void run() {
							addMessage(new ChatMessage(now, ChatMessage.IN, p.sender,
									text != null ? text : getString(R.string.enc_wrong_password), true), true);
						}
					});
				}
			});
			return;
		}
		if (p.sender.length() > 0 && p.message != null && p.message.length() > 0)
			addMessage(new ChatMessage(now, ChatMessage.IN, p.sender, p.message, false), true);
		else
			addMessage(new ChatMessage(now, ChatMessage.IN, getString(R.string.unknown), p.text, false), true);
	}

	// ---------------------------------------------------------------- misc

	private void addMessage(ChatMessage m, boolean save) {
		if (save && prefs.getBoolean("saveMessages", true))
			store.add(m);
		adapter.add(m);
		chat.setSelection(adapter.getCount() - 1);
	}

	private void showListening() {
		if (!engine.isTransmitting() && !encrypting)
			status.setText(getString(R.string.listening) + (Modem.variant().equals("soft") ? " (soft-float)" : ""));
	}

	private void toast(String s) {
		Toast.makeText(this, s, Toast.LENGTH_LONG).show();
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
