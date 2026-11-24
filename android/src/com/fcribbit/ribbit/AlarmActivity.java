package com.fcribbit.ribbit;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full screen alarm. Counts down (a testing delay, so there's time to plug
 * headphones in after the alarm frame was received), then rings until a key or
 * the screen is touched.
 */
public class AlarmActivity extends Activity {
	public static final String EXTRA_SENDER = "sender";
	public static final String EXTRA_LOCAL_TEST = "localTest";
	private static final long MAX_RING_MS = 10 * 60 * 1000; // safety stop

	/** true from the moment the ring starts until it stops (no transmissions then) */
	public static volatile boolean ringing;

	private final Handler handler = new Handler();
	private AlarmPlayer player;
	private PowerManager.WakeLock screenLock;
	private TextView title, info, detail;
	private Button cancel;
	private int remaining;
	private boolean started, finished;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
				| WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
				| WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
				| WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		screenLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "ribbit-alarm");
		screenLock.setReferenceCounted(false);
		screenLock.acquire(MAX_RING_MS + 120000);

		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setGravity(Gravity.CENTER);
		root.setBackgroundColor(0xFF7A0E0E);
		root.setPadding(12, 12, 12, 12);
		title = text(root, 30, true);
		info = text(root, 16, false);
		detail = text(root, 11, false);
		cancel = new Button(this);
		cancel.setText(R.string.cancel);
		cancel.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				done();
			}
		});
		root.addView(cancel);
		setContentView(root);

		String sender = getIntent().getStringExtra(EXTRA_SENDER);
		String who = sender == null ? "" : Payload.describeSender(sender);
		title.setText(getString(R.string.alarm_title) + (who.length() > 0 ? "\n" + who : ""));
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		remaining = parseInt(prefs.getString("alarmDelay", "10"), 10);
		player = new AlarmPlayer(this);
		handler.post(tick);
	}

	private TextView text(LinearLayout root, int sp, boolean bold) {
		TextView t = new TextView(this);
		t.setTextSize(sp);
		t.setTextColor(Color.WHITE);
		t.setGravity(Gravity.CENTER);
		if (bold)
			t.setTypeface(null, android.graphics.Typeface.BOLD);
		t.setPadding(0, 4, 0, 4);
		root.addView(t);
		return t;
	}

	private final Runnable tick = new Runnable() {
		public void run() {
			if (finished)
				return;
			if (remaining > 0) {
				info.setText(getString(R.string.alarm_countdown, remaining));
				--remaining;
				handler.postDelayed(this, 1000);
			} else {
				ring();
			}
		}
	};

	private void ring() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		ringing = true;
		started = true;
		tellService(RibbitService.ACTION_ALARM_RINGING);
		player.start(prefs.getString("alarmRoute", AlarmPlayer.SPEAKERPHONE));
		info.setText(R.string.alarm_ringing);
		detail.setText(player.result());
		cancel.setVisibility(View.GONE);
		handler.postDelayed(new Runnable() {
			public void run() {
				done();
			}
		}, MAX_RING_MS);
	}

	private void done() {
		if (finished)
			return;
		finished = true;
		handler.removeCallbacksAndMessages(null);
		player.stop();
		ringing = false;
		tellService(RibbitService.ACTION_ALARM_DONE);
		if (screenLock.isHeld())
			screenLock.release();
		finish();
	}

	private void tellService(String action) {
		if (getIntent().getBooleanExtra(EXTRA_LOCAL_TEST, false) && RibbitService.ACTION_ALARM_RINGING.equals(action))
			return; // local test: nothing to pause
		startService(new Intent(this, RibbitService.class).setAction(action));
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (started)
			done();
		return true; // during the countdown keys do nothing (use Cancel)
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (started && event.getAction() == MotionEvent.ACTION_DOWN) {
			done();
			return true;
		}
		return super.onTouchEvent(event);
	}

	@Override
	protected void onPause() {
		super.onPause();
		// the power key turns the screen off: that counts as a key too
		if (started)
			done();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (!finished)
			done();
	}

	private static int parseInt(String s, int def) {
		try {
			return Integer.parseInt(s);
		} catch (Exception e) {
			return def;
		}
	}
}
