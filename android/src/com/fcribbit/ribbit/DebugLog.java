package com.fcribbit.ribbit;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/** In-memory diagnostic log (last 300 lines), shown in Menu → Diagnostics. */
public final class DebugLog {
	private static final int MAX = 300;
	private static final LinkedList<String> lines = new LinkedList<String>();
	private static final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

	private DebugLog() {}

	public static void log(String what) {
		String line;
		synchronized (time) {
			line = time.format(new Date()) + "  " + what;
		}
		synchronized (lines) {
			lines.add(line);
			while (lines.size() > MAX)
				lines.removeFirst();
		}
		android.util.Log.i("Ribbit", what);
	}

	public static String dump() {
		StringBuilder sb = new StringBuilder();
		synchronized (lines) {
			for (String l : lines)
				sb.append(l).append('\n');
		}
		return sb.toString();
	}

	public static void clear() {
		synchronized (lines) {
			lines.clear();
		}
	}

	/** short, single-line preview of a message for the log */
	public static String preview(String s) {
		if (s == null)
			return "null";
		s = s.replace('\n', ' ').replace('\0', '·');
		return s.length() > 40 ? s.substring(0, 40) + "…" : s;
	}
}
