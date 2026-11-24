import com.fcribbit.ribbit.*;
import java.io.*;
import java.nio.*;

/**
 * Runs the app's Java classes (Payload, RibbitCrypto, Modem) against a host build of
 * the same JNI code.  Usage:
 *   HostTest tx  out.wav  password text     -> encrypt + encode like MainActivity
 *   HostTest rx  in.wav   password          -> decode + decrypt like MainActivity
 */
public class HostTest {
	static final String HEADER = Payload.header("Fermín", "LU1ABC", "GF05RK", "+54 9 11");

	public static void main(String[] a) throws Exception {
		if (!Modem.load()) throw new RuntimeException("native load failed");
		if (a[0].equals("tx")) {
			String body = a[2].isEmpty() ? a[3] : RibbitCrypto.encrypt(a[3], a[2], HEADER);
			byte[] payload = Payload.pack(HEADER, body);
			System.out.println("payload: " + Payload.fromUtf8(payload, Payload.utf8(HEADER + "&=" + body).length));
			Modem.encoderInit(payload);
			ByteArrayOutputStream pcm = new ByteArrayOutputStream();
			short[] block = new short[1600];
			boolean done = false;
			while (!done) {
				done = Modem.encoderRead(block, block.length, 0.75f);
				ByteBuffer bb = ByteBuffer.allocate(block.length * 2).order(ByteOrder.LITTLE_ENDIAN);
				for (short s : block) bb.putShort(s);
				pcm.write(bb.array());
			}
			writeWav(a[1], pcm.toByteArray());
		} else {
			short[] pcm = readWav(a[1]);
			byte[] payload = new byte[256];
			int frames = 0;
			for (int pos = 0; pos < pcm.length; pos += 480) {
				int n = Math.min(480, pcm.length - pos);
				short[] chunk = new short[480];
				System.arraycopy(pcm, pos, chunk, 0, n);
				int r = Modem.decoderFeed(chunk, n, payload);
				if (r == Modem.NOTHING) continue;
				frames++;
				System.out.println("frame result=" + r);
				if (r < 0) continue;
				Payload.Parsed p = Payload.parse(payload);
				System.out.println("sender : " + p.sender + "  [" + Payload.describeSender(p.sender) + "]");
				System.out.println("message: " + p.message);
				if (RibbitCrypto.isEncrypted(p.message))
					System.out.println("decrypt: " + RibbitCrypto.decrypt(p.message, a[2], p.sender));
			}
			if (frames == 0) { System.out.println("NO FRAME"); System.exit(1); }
		}
	}

	static void writeWav(String f, byte[] data) throws IOException {
		ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		h.put("RIFF".getBytes()).putInt(36 + data.length).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1)
			.putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
			.put("data".getBytes()).putInt(data.length);
		try (FileOutputStream o = new FileOutputStream(f)) { o.write(h.array()); o.write(data); }
	}

	static short[] readWav(String f) throws IOException {
		byte[] all = java.nio.file.Files.readAllBytes(new File(f).toPath());
		ByteBuffer b = ByteBuffer.wrap(all, 44, all.length - 44).order(ByteOrder.LITTLE_ENDIAN);
		short[] s = new short[(all.length - 44) / 2];
		for (int i = 0; i < s.length; i++) s[i] = b.getShort();
		return s;
	}
}
