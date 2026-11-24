package com.fcribbit.ribbit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.preference.PreferenceManager;

/**
 * Receives the periodic AlarmManager wake-up (intermittent listening) and the boot
 * broadcast. AlarmManager only keeps the CPU awake during onReceive(), so a wake
 * lock is taken here and handed over to the service.
 */
public class WakeReceiver extends BroadcastReceiver {
	private static PowerManager.WakeLock lock;

	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();
		if (RibbitService.ACTION_CHECK.equals(action)) {
			synchronized (WakeReceiver.class) {
				if (lock == null) {
					PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
					lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ribbit-check");
					lock.setReferenceCounted(false);
				}
				lock.acquire(10000);
			}
			context.startService(new Intent(context, RibbitService.class).setAction(RibbitService.ACTION_CHECK));
		} else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
			String mode = PreferenceManager.getDefaultSharedPreferences(context)
					.getString("listenMode", RibbitService.MODE_INTERMITTENT);
			if (!RibbitService.MODE_FOREGROUND.equals(mode))
				context.startService(new Intent(context, RibbitService.class).setAction(RibbitService.ACTION_START));
		}
	}

	static void releaseLock() {
		synchronized (WakeReceiver.class) {
			if (lock != null && lock.isHeld())
				lock.release();
		}
	}
}
