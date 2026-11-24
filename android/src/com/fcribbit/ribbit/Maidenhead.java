package com.fcribbit.ribbit;

/** Port of latLonToQth() from the web app: 6 character grid square, all upper case. */
public final class Maidenhead {
	private Maidenhead() {}

	public static String fromLatLon(double lat, double lon) {
		if (lat < -90 || lat > 90 || lon < -180 || lon > 180)
			return "";
		double y = (lat + 90) / 10;
		double x = (lon + 180) / 20;
		StringBuilder qth = new StringBuilder(6);
		for (int i = 1; i < 4; ++i) {
			int latInt = (int) Math.floor(y);
			int lonInt = (int) Math.floor(x);
			if (i % 2 == 1) {
				qth.append((char) ('A' + lonInt)).append((char) ('A' + latInt));
				y = round6((y - latInt) * 10);
				x = round6((x - lonInt) * 10);
			} else {
				qth.append(lonInt).append(latInt);
				y = round6((y - latInt) * 24);
				x = round6((x - lonInt) * 24);
			}
		}
		return qth.toString();
	}

	/** the web app rounds with toFixed(6) between steps */
	private static double round6(double v) {
		return Math.round(v * 1e6) / 1e6;
	}
}
