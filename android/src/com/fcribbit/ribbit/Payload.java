package com.fcribbit.ribbit;

import java.io.UnsupportedEncodingException;

/**
 * The 256 byte Ribbit payload, laid out exactly like the web app:
 * UTF-8 of "name|callsign|grid|phone&=body", zero padded (and cut) to 256 bytes.
 */
public final class Payload {
	public static final String SEPARATOR = "&=";

	private Payload() {}

	public static String header(String name, String callsign, String grid, String phone) {
		return name + "|" + callsign + "|" + grid + "|" + phone;
	}

	public static byte[] utf8(String s) {
		try {
			return s.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

	public static String fromUtf8(byte[] b, int len) {
		try {
			return new String(b, 0, len, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

	/** Free bytes for the body after "header&=". */
	public static int room(String header) {
		return Modem.PAYLOAD_BYTES - utf8(header + SEPARATOR).length;
	}

	public static byte[] pack(String header, String body) {
		byte[] src = utf8(header + SEPARATOR + body);
		byte[] out = new byte[Modem.PAYLOAD_BYTES];
		System.arraycopy(src, 0, out, 0, Math.min(src.length, out.length));
		return out;
	}

	/** A decoded payload, split the way the web app's fetchDecoded() does. */
	public static final class Parsed {
		public final String text;    // whole payload without trailing NULs
		public final String sender;  // text before the first "&=", "" if none
		public final String message; // text between the first and second "&=", null if none

		Parsed(String text, String sender, String message) {
			this.text = text;
			this.sender = sender;
			this.message = message;
		}
	}

	public static Parsed parse(byte[] payload) {
		int len = payload.length;
		while (len > 0 && payload[len - 1] == 0)
			--len;
		String str = fromUtf8(payload, len);
		int sep = str.indexOf(SEPARATOR);
		if (sep < 0)
			return new Parsed(str, str, null);
		String sender = str.substring(0, sep);
		String rest = str.substring(sep + SEPARATOR.length());
		int next = rest.indexOf(SEPARATOR);
		String message = stripNul(next < 0 ? rest : rest.substring(0, next));
		return new Parsed(str, stripNul(sender), message);
	}

	private static String stripNul(String s) {
		int end = s.length();
		while (end > 0 && s.charAt(end - 1) == '\0')
			--end;
		return s.substring(0, end);
	}

	/** "name (callsign) · grid" for display; the header fields are name|callsign|grid|phone. */
	public static String describeSender(String header) {
		String[] f = header.split("\\|", -1);
		String name = f.length > 0 ? f[0].trim() : "";
		String call = f.length > 1 ? f[1].trim() : "";
		String grid = f.length > 2 ? f[2].trim() : "";
		StringBuilder sb = new StringBuilder();
		if (name.length() > 0 && !name.equals("null"))
			sb.append(name);
		if (call.length() > 0 && !call.equals("null")) {
			if (sb.length() > 0)
				sb.append(" (").append(call).append(')');
			else
				sb.append(call);
		}
		if (grid.length() > 0 && !grid.equals("null")) {
			if (sb.length() > 0)
				sb.append(" · ");
			sb.append(grid);
		}
		return sb.toString();
	}
}
