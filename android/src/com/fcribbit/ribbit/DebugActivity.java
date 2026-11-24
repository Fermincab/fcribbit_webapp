package com.fcribbit.ribbit;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.ClipboardManager;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Shows DebugLog plus the current audio state; refreshes every second. */
public class DebugActivity extends Activity {
	private final Handler handler = new Handler();
	private TextView text;
	private ScrollView scroll;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		LinearLayout buttons = new LinearLayout(this);
		Button copy = new Button(this);
		copy.setText(R.string.debug_copy);
		copy.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
				cm.setText(header() + DebugLog.dump());
				Toast.makeText(DebugActivity.this, R.string.debug_copied, Toast.LENGTH_SHORT).show();
			}
		});
		Button clear = new Button(this);
		clear.setText(R.string.debug_clear);
		clear.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				DebugLog.clear();
				refresh();
			}
		});
		buttons.addView(copy);
		buttons.addView(clear);
		root.addView(buttons);
		scroll = new ScrollView(this);
		text = new TextView(this);
		text.setTextSize(10);
		text.setTypeface(android.graphics.Typeface.MONOSPACE);
		scroll.addView(text);
		root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT, 0, 1));
		setContentView(root);
	}

	private String header() {
		AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		return "modem=" + Modem.variant() + " audioMode=" + am.getMode() + " speakerphone=" + am.isSpeakerphoneOn()
				+ " headset=" + am.isWiredHeadsetOn() + "\n\n";
	}

	private void refresh() {
		text.setText(header() + DebugLog.dump());
		scroll.post(new Runnable() {
			public void run() {
				scroll.fullScroll(View.FOCUS_DOWN);
			}
		});
	}

	private final Runnable tick = new Runnable() {
		public void run() {
			refresh();
			handler.postDelayed(this, 1000);
		}
	};

	@Override
	protected void onResume() {
		super.onResume();
		handler.post(tick);
	}

	@Override
	protected void onPause() {
		super.onPause();
		handler.removeCallbacks(tick);
	}
}
