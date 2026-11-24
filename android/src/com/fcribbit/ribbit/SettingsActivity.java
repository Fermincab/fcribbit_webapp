package com.fcribbit.ribbit;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.widget.Toast;

public class SettingsActivity extends PreferenceActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
	private static final String[] TEXT_KEYS = { "name", "callsign", "gridsquare", "phone" };

	private final Handler handler = new Handler();
	private LocationManager locationManager;
	private LocationListener locationListener;
	private ProgressDialog progress;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.settings);
		findPreference("gps").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
			public boolean onPreferenceClick(Preference p) {
				locate();
				return true;
			}
		});
		findPreference("alarmTest").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
			public boolean onPreferenceClick(Preference p) {
				startActivity(new Intent(SettingsActivity.this, AlarmActivity.class));
				return true;
			}
		});
		for (String k : TEXT_KEYS)
			updateSummary(k);
		for (String k : LIST_KEYS)
			updateSummary(k);
		updateSummary("encryptionPassword");
		updateBattery();
	}

	private static final String[] LIST_KEYS = { "txLevel", "micSource", "listenMode", "checkInterval",
			"sessionMinutes" };

	private void updateBattery() {
		SharedPreferences prefs = getPreferenceScreen().getSharedPreferences();
		Preference p = findPreference("battery");
		long t0 = prefs.getLong("battStartTime", 0), t1 = prefs.getLong("battLastTime", 0);
		int l0 = prefs.getInt("battStartLevel", -1), l1 = prefs.getInt("battLastLevel", -1);
		double hours = (t1 - t0) / 3600000.0;
		if (t0 == 0 || l0 < 0 || hours < 0.25) {
			p.setSummary(R.string.battery_none);
			return;
		}
		p.setSummary(getString(R.string.battery_summary, (l0 - l1) / hours, hours, l1));
	}

	@Override
	protected void onResume() {
		super.onResume();
		getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
	}

	@Override
	protected void onPause() {
		super.onPause();
		getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
		stopLocating();
	}

	public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
		updateSummary(key);
		// the background service picks up the listening mode / interval
		if (key.equals("listenMode") || key.equals("checkInterval"))
			startService(new Intent(this, RibbitService.class).setAction(RibbitService.ACTION_START));
	}

	private void updateSummary(String key) {
		Preference p = findPreference(key);
		if (p == null)
			return;
		if (p instanceof EditTextPreference) {
			String v = ((EditTextPreference) p).getText();
			boolean empty = v == null || v.length() == 0;
			if (key.equals("encryptionPassword"))
				p.setSummary(empty ? R.string.password_not_set : R.string.password_set);
			else
				p.setSummary(empty ? getString(R.string.not_set) : v);
		} else if (p instanceof ListPreference) {
			CharSequence e = ((ListPreference) p).getEntry();
			p.setSummary(e == null ? "" : e);
		}
	}

	// ------------------------------------------------------- grid from GPS

	private void locate() {
		locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		String provider = null;
		if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
			provider = LocationManager.GPS_PROVIDER;
		else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
			provider = LocationManager.NETWORK_PROVIDER;
		if (provider == null) {
			Toast.makeText(this, R.string.gps_off, Toast.LENGTH_LONG).show();
			return;
		}
		locationListener = new LocationListener() {
			public void onLocationChanged(Location location) {
				stopLocating();
				setGrid(Maidenhead.fromLatLon(location.getLatitude(), location.getLongitude()));
			}

			public void onStatusChanged(String p, int status, Bundle extras) {
			}

			public void onProviderEnabled(String p) {
			}

			public void onProviderDisabled(String p) {
			}
		};
		progress = ProgressDialog.show(this, null, getString(R.string.gps_waiting), true, true,
				new DialogInterface.OnCancelListener() {
					public void onCancel(DialogInterface d) {
						stopLocating();
					}
				});
		locationManager.requestLocationUpdates(provider, 0, 0, locationListener);
		handler.postDelayed(timeout, 90000);
	}

	private final Runnable timeout = new Runnable() {
		public void run() {
			if (locationListener != null) {
				stopLocating();
				Toast.makeText(SettingsActivity.this, R.string.gps_timeout, Toast.LENGTH_LONG).show();
			}
		}
	};

	private void stopLocating() {
		handler.removeCallbacks(timeout);
		if (locationManager != null && locationListener != null)
			locationManager.removeUpdates(locationListener);
		locationListener = null;
		if (progress != null) {
			progress.dismiss();
			progress = null;
		}
	}

	private void setGrid(String grid) {
		if (grid.length() == 0)
			return;
		((EditTextPreference) findPreference("gridsquare")).setText(grid);
		updateSummary("gridsquare");
	}
}
