package com.fcribbit.ribbit;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full screen alarm: rings on the loudspeaker as soon as it opens, until the
 * screen is touched or any key is pressed (power key included), or for at most
 * one minute.
 */
public class AlarmActivity extends Activity {
	public static final String EXTRA_SENDER = "sender";
	private static final long MAX_RING_MS = 60 * 1000;

	private final Handler handler = new Handler();
	private AlarmPlayer player;
	private PowerManager.WakeLock screenLock;
	private boolean finished;

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
		screenLock.acquire(MAX_RING_MS + 10000);

		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setGravity(Gravity.CENTER);
		root.setBackgroundColor(0xFF7A0E0E);
		root.setPadding(12, 12, 12, 12);
		String sender = getIntent().getStringExtra(EXTRA_SENDER);
		String who = sender == null ? "" : Payload.describeSender(sender);
		text(root, 30, true).setText(getString(R.string.alarm_title) + (who.length() > 0 ? "\n" + who : ""));
		text(root, 16, false).setText(R.string.alarm_ringing);
		setContentView(root);

		// the power key turns the screen off: that counts as a key too. (onPause can't be used
		// for this: on 2.3 an activity shown over the lock screen may get paused while waking up)
		registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
		player = new AlarmPlayer(this);
		player.start();
		routeChanged();
		handler.postDelayed(new Runnable() {
			public void run() {
				done();
			}
		}, MAX_RING_MS);
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

	private final BroadcastReceiver screenOff = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			done();
		}
	};

	private void done() {
		if (finished)
			return;
		finished = true;
		try {
			unregisterReceiver(screenOff);
		} catch (Exception e) {
			// ignore
		}
		handler.removeCallbacksAndMessages(null);
		player.stop();
		routeChanged();
		if (screenLock.isHeld())
			screenLock.release();
		finish();
	}

	/** turning the speakerphone on/off can break the open microphone: let the service reopen it */
	private void routeChanged() {
		startService(new Intent(this, RibbitService.class).setAction(RibbitService.ACTION_AUDIO_ROUTE));
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		done();
		return true;
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (event.getAction() == MotionEvent.ACTION_DOWN) {
			done();
			return true;
		}
		return super.onTouchEvent(event);
	}

	/** the Home key (which onKeyDown never sees) */
	@Override
	protected void onUserLeaveHint() {
		super.onUserLeaveHint();
		done();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		done();
	}
}
