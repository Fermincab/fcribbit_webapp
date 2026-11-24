package com.fcribbit.ribbit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
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

public class MainActivity extends Activity implements RibbitService.UiListener {
	private static final int MENU_SETTINGS = 1;
	private static final int MENU_CLEAR = 2;

	private final Handler handler = new Handler();
	/** PBKDF2 runs here so the UI doesn't stall */
	private final ExecutorService crypto = Executors.newSingleThreadExecutor();

	private SharedPreferences prefs;
	private MessageAdapter adapter;
	private RibbitService service;
	private boolean resumed;

	private ListView chat;
	private EditText input;
	private Button send, alarm;
	private TextView status;
	private TextView counter;
	private ProgressBar level;

	private boolean encrypting;
	private boolean askedForSettings;
	private int shownCount = -1;

	private final ServiceConnection connection = new ServiceConnection() {
		public void onServiceConnected(ComponentName name, IBinder binder) {
			service = ((RibbitService.LocalBinder) binder).service();
			if (!service.modemOk()) {
				new AlertDialog.Builder(MainActivity.this).setMessage(R.string.native_error)
						.setPositiveButton(android.R.string.ok, null).show();
			}
			service.setUi(MainActivity.this, resumed);
			onChanged();
		}

		public void onServiceDisconnected(ComponentName name) {
			service = null;
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		setVolumeControlStream(AudioManager.STREAM_MUSIC);
		PreferenceManager.setDefaultValues(this, R.xml.settings, false);
		prefs = PreferenceManager.getDefaultSharedPreferences(this);
		adapter = new MessageAdapter(this);

		chat = (ListView) findViewById(R.id.chat);
		input = (EditText) findViewById(R.id.message);
		send = (Button) findViewById(R.id.send);
		alarm = (Button) findViewById(R.id.alarm);
		status = (TextView) findViewById(R.id.status);
		counter = (TextView) findViewById(R.id.counter);
		level = (ProgressBar) findViewById(R.id.level);
		chat.setAdapter(adapter);

		send.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				sendMessage();
			}
		});
		alarm.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				confirmAlarm();
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

		Intent svc = new Intent(this, RibbitService.class).setAction(RibbitService.ACTION_START);
		startService(svc);
		bindService(svc, connection, BIND_AUTO_CREATE);
	}

	@Override
	protected void onResume() {
		super.onResume();
		resumed = true;
		adapter.setHideSenders(prefs.getBoolean("hideSenders", false));
		updateCounter();
		if (service != null)
			service.setUi(this, true);
		handler.post(clock);
		// like the web app: open the settings when the station isn't set up yet
		if (!askedForSettings && prefs.getString("name", "").length() == 0) {
			askedForSettings = true;
			openSettings();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		resumed = false;
		handler.removeCallbacks(clock);
		if (service != null)
			service.setUi(this, false);
		level.setProgress(0);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		crypto.shutdown();
		if (service != null)
			service.setUi(null, false);
		unbindService(connection);
	}

	/** refreshes the status line (session countdown) */
	private final Runnable clock = new Runnable() {
		public void run() {
			updateStatus();
			handler.postDelayed(this, 1000);
		}
	};

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
								if (service != null)
									service.clearMessages();
							}
						}).setNegativeButton(R.string.no, null).show();
				return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void openSettings() {
		startActivity(new Intent(this, SettingsActivity.class));
	}

	// ------------------------------------------------------------- sending

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
		int max = encryptionOn() ? Math.max(RibbitCrypto.maxPlaintextBytes(room), 0) : room;
		counter.setText(getString(encryptionOn() ? R.string.enc_bytes_left : R.string.bytes_left, max - used));
		counter.setTextColor(used > max ? 0xFFFF6060 : 0xFF9AA59A);
	}

	private boolean ready() {
		return service != null && service.modemOk() && !encrypting;
	}

	private void sendMessage() {
		if (!ready())
			return;
		final String plaintext = input.getText().toString();
		if (plaintext.length() < 1)
			return;
		final String header = header();
		int room = Payload.room(header);
		if (!encryptionOn()) {
			if (Payload.utf8(plaintext).length > room) {
				onToast(getString(R.string.too_long, Math.max(room, 0)));
				return;
			}
			input.setText("");
			service.sendText(header, plaintext, plaintext, false);
			return;
		}
		final String password = prefs.getString("encryptionPassword", "");
		if (password.length() == 0) {
			onToast(getString(R.string.set_password));
			return;
		}
		int maxBytes = RibbitCrypto.maxPlaintextBytes(room);
		if (Payload.utf8(plaintext).length > maxBytes) {
			onToast(getString(R.string.enc_too_long, Math.max(maxBytes, 0)));
			return;
		}
		encrypting = true;
		updateStatus();
		crypto.execute(new Runnable() {
			public void run() {
				final String body = RibbitCrypto.encrypt(plaintext, password, header);
				handler.post(new Runnable() {
					public void run() {
						encrypting = false;
						if (body == null) {
							onToast(getString(R.string.encryption_failed));
						} else if (service != null) {
							input.setText("");
							service.sendText(header, body, plaintext, true);
						}
						updateStatus();
					}
				});
			}
		});
	}

	private void confirmAlarm() {
		if (!ready())
			return;
		new AlertDialog.Builder(this).setMessage(R.string.alarm_confirm)
				.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int w) {
						if (service != null)
							service.sendAlarm();
					}
				}).setNegativeButton(R.string.no, null).show();
	}

	// ------------------------------------------------------------ service

	public void onChanged() {
		if (service == null)
			return;
		int count = service.messages().size();
		if (count != shownCount) {
			shownCount = count;
			adapter.setAll(service.messages());
			chat.setSelection(adapter.getCount() - 1);
		}
		updateStatus();
	}

	public void onLevel(int percent) {
		level.setProgress(percent);
	}

	public void onToast(String text) {
		Toast.makeText(this, text, Toast.LENGTH_LONG).show();
	}

	private void updateStatus() {
		if (service == null)
			return;
		SessionLogic s = service.session();
		String text;
		if (!service.modemOk())
			text = getString(R.string.native_error_short);
		else if (encrypting)
			text = getString(R.string.encrypting);
		else if (s.transmittingTone())
			text = getString(R.string.status_calling);
		else if (service.transmitting() || s.transmitting())
			text = getString(R.string.transmitting);
		else {
			switch (s.state()) {
				case OPENING:
					text = getString(R.string.status_waiting_ack, s.attempts(), SessionLogic.MAX_ATTEMPTS);
					break;
				case WOKEN:
					text = getString(R.string.status_woken);
					break;
				case SESSION:
					long sec = s.remainingMs() / 1000;
					text = getString(R.string.status_session, String.format("%d:%02d", sec / 60, sec % 60));
					break;
				default:
					text = getString(R.string.status_no_session);
			}
		}
		if (Modem.variant().equals("soft"))
			text += " (soft-float)";
		status.setText(text);
	}
}
