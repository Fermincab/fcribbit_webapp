import com.fcribbit.ribbit.*;
import java.util.*;

/**
 * Discrete-time simulation of two phones running SessionLogic over a half-duplex
 * radio channel with VOX, intermittent listening and frame loss.
 */
public class SessionSim {
	static final int STEP = 100;            // ms per simulation step
	static final int FRAME_MS = 1800;       // one Ribbit frame on air
	static final int GAP_MS = 300;          // between the wake tone and the frame
	static final int VOX_HANG = 1500;       // radio keeps transmitting after the audio stops
	static final int CHECK_MS = 1000;       // intermittent listening window
	static final int MIN_TONE_IN_WINDOW = 600;

	static long now;
	static Random rnd;
	static int fails;

	static void check(String what, boolean ok) {
		System.out.println((ok ? "ok   " : "FAIL ") + what);
		if (!ok) fails++;
	}

	static final class Tx {
		Phone from; long start, toneEnd, frameStart, frameEnd, keyedUntil; byte[] payload; boolean collided;
		final Map<Phone, Long> toneHeardMs = new HashMap<Phone, Long>();
		final Set<Phone> missed = new HashSet<Phone>();
	}

	static final List<Tx> air = new ArrayList<Tx>();

	static final class Phone implements SessionLogic.Actions {
		final String name;
		final SessionLogic logic;
		final String header;
		int interval = 15000;
		long phase;
		boolean powered = true;
		double rxLoss;          // probability of losing a received frame
		Set<String> dropOnce = new HashSet<String>(); // control words to drop the first time we receive them
		final List<String> chat = new ArrayList<String>();
		final List<SessionLogic.Event> events = new ArrayList<SessionLogic.Event>();
		int alarms;
		Tx myTx;
		long awakeMs;           // time spent awake (continuous listening, transmitting or checking)
		Phone peer;

		Phone(String name, String call) {
			this.name = name;
			this.header = Payload.header(name, call, "GF05", "");
			logic = new SessionLogic(new SessionLogic.Clock() { public long now() { return now; } }, this,
					new SessionLogic.HeaderSource() { public String header() { return header; } });
		}

		void say(String text) {
			logic.send(Payload.pack(header, text), new ChatMessage(now, ChatMessage.OUT, header, text, false));
		}

		void alarm() {
			logic.send(Payload.pack(header, SessionLogic.CONTROL + SessionLogic.ALARM),
					new ChatMessage(now, ChatMessage.ALERT, "", "alarm sent", false));
		}

		public void transmit(SessionLogic.Outgoing o, int toneMs) {
			Tx t = new Tx();
			t.from = this; t.start = now; t.toneEnd = now + toneMs;
			t.frameStart = t.toneEnd + (toneMs > 0 ? GAP_MS : 100);
			t.frameEnd = t.frameStart + FRAME_MS;
			t.keyedUntil = t.frameEnd + VOX_HANG;
			t.payload = o.payload;
			myTx = t;
			air.add(t);
		}

		public void deliver(ChatMessage m) { chat.add((m.type == ChatMessage.OUT ? "> " : m.type == ChatMessage.IN ? "< " : "* ") + m.body); }
		public void alarm(String sender) { alarms++; chat.add("! alarm from " + Payload.describeSender(sender)); }
		public void event(SessionLogic.Event e, int d) { events.add(e); }
		public void stateChanged() {}
		public boolean canTransmit() { return true; }

		boolean continuous() { return powered && (logic.busy() || myTx != null); }
		boolean radioKeyed() { for (Tx t : air) if (t.from == this && now >= t.start && now < t.keyedUntil) return true; return false; }
		boolean checking() { return powered && !continuous() && ((now - phase) % interval + interval) % interval < CHECK_MS; }
	}

	/** run the world for ms milliseconds */
	static void run(Phone a, Phone b, long ms) {
		long end = now + ms;
		while (now < end) {
			now += STEP;
			for (Phone p : new Phone[] { a, b }) {
				if (!p.powered) continue;
				if (p.continuous() || p.checking()) p.awakeMs += STEP;
				// our own audio finished?
				if (p.myTx != null && now >= p.myTx.frameEnd) { p.myTx = null; p.logic.onTxDone(); }
				Phone q = p.peer;
				for (Tx t : air) {
					if (t.from != q) continue;
					boolean listening = (p.continuous() || p.checking()) && !p.radioKeyed();
					// tone: heard once enough of it overlapped continuous listening or a check window
					if (now > t.start && now <= t.toneEnd && listening) {
						t.toneHeardMs.merge(p, (long) STEP, Long::sum);
						if (t.toneHeardMs.get(p) == MIN_TONE_IN_WINDOW) p.logic.onToneHeard();
					}
					if (now > t.start && now <= t.toneEnd && !listening) t.toneHeardMs.put(p, 0L); // window must be contiguous
					// frame: we must have been listening during the whole frame
					if (now > t.frameStart && now <= t.frameEnd && !(p.continuous() && !p.radioKeyed())) t.missed.add(p);
					if (now == t.frameEnd && !t.missed.contains(p) && !t.collided) {
						Payload.Parsed parsed = Payload.parse(t.payload);
						String ctl = parsed.message != null && parsed.message.startsWith(SessionLogic.CONTROL) ? parsed.message.substring(5) : "";
						if (p.dropOnce.remove(ctl)) continue;
						if (rnd.nextDouble() < p.rxLoss) continue;
						p.logic.onFrame(parsed.sender, parsed.message);
					}
				}
				if (p.logic.busy()) p.logic.tick();
			}
			// collisions: overlapping audio from both phones
			for (Tx x : air) for (Tx y : air)
				if (x.from != y.from && x.start < y.frameEnd && y.start < x.frameEnd) { x.collided = true; y.collided = true; }
			air.removeIf(t -> now > t.keyedUntil + 1000);
		}
	}

	static Phone[] pair(long seed) {
		now = 0; air.clear(); rnd = new Random(seed);
		Phone a = new Phone("Ana", "LU1AAA"), b = new Phone("Beto", "LU2BBB");
		a.peer = b; b.peer = a;
		a.phase = rnd.nextInt(15000); b.phase = rnd.nextInt(15000);
		run(a, b, 20000);
		return new Phone[] { a, b };
	}

	static int count(List<String> l, String s) { int n = 0; for (String x : l) if (x.equals(s)) n++; return n; }

	public static void main(String[] args) {
		// 1. basic: open with the first message, reply, close after 5 min, reopen
		Phone[] p = pair(1); Phone a = p[0], b = p[1];
		a.say("hola");
		check("A transmits the wake tone first", a.logic.transmittingTone());
		run(a, b, 30000);
		check("B got 'hola' once", count(b.chat, "< hola") == 1);
		check("both in SESSION", a.logic.state() == SessionLogic.State.SESSION && b.logic.state() == SessionLogic.State.SESSION);
		check("A initiator, B not", a.logic.isInitiator() && !b.logic.isInitiator());
		check("A saw OPENED", a.events.contains(SessionLogic.Event.OPENED));
		b.say("hola, te leo");
		check("B replies without tone", b.logic.transmitting() && !b.logic.transmittingTone());
		run(a, b, 10000);
		check("A got the reply", count(a.chat, "< hola, te leo") == 1);
		a.say("segundo mensaje"); run(a, b, 6000);
		check("second message without tone, received", count(b.chat, "< segundo mensaje") == 1);
		run(a, b, 4 * 60000);
		check("still in session 4 min after last message", b.logic.state() == SessionLogic.State.SESSION);
		run(a, b, 45000);
		check("closing warning shown", a.events.contains(SessionLogic.Event.CLOSING_SOON) && b.events.contains(SessionLogic.Event.CLOSING_SOON));
		run(a, b, 75000);
		check("both back to SLEEP after 5 min", a.logic.state() == SessionLogic.State.SLEEP && b.logic.state() == SessionLogic.State.SLEEP);
		check("both saw CLOSED", a.events.contains(SessionLogic.Event.CLOSED) && b.events.contains(SessionLogic.Event.CLOSED));
		a.say("otra vez");
		check("after close the tone is sent again", a.logic.transmittingTone());
		run(a, b, 30000);
		check("reopened and delivered", count(b.chat, "< otra vez") == 1 && b.logic.state() == SessionLogic.State.SESSION);

		// 2. lost SESSION_OK: A repeats tone+frame, B confirms again, no duplicate in B's chat
		p = pair(2); a = p[0]; b = p[1];
		a.dropOnce.add(SessionLogic.SESSION_OK);
		a.say("¿me escuchás?");
		run(a, b, 90000);
		check("ACK lost -> A retried", a.events.contains(SessionLogic.Event.RETRY));
		check("session opened after retry", a.logic.state() == SessionLogic.State.SESSION && b.logic.state() == SessionLogic.State.SESSION);
		check("no duplicate at B", count(b.chat, "< ¿me escuchás?") == 1);

		// 3. B is off: 3 attempts then NO_RESPONSE
		p = pair(3); a = p[0]; b = p[1];
		b.powered = false;
		a.say("¿hay alguien?");
		run(a, b, 3 * 60000);
		check("B off -> NO_RESPONSE after 3 attempts", a.events.contains(SessionLogic.Event.NO_RESPONSE) && a.logic.attempts() == 3);
		check("A back to SLEEP", a.logic.state() == SessionLogic.State.SLEEP);

		// 4. messages typed while opening wait, then go out in order
		p = pair(4); a = p[0]; b = p[1];
		a.say("uno"); a.say("dos"); a.say("tres");
		check("2 and 3 queued while opening", a.events.contains(SessionLogic.Event.QUEUED));
		run(a, b, 60000);
		int i1 = b.chat.indexOf("< uno"), i2 = b.chat.indexOf("< dos"), i3 = b.chat.indexOf("< tres");
		check("B got uno, dos, tres in order", i1 >= 0 && i1 < i2 && i2 < i3);

		// 5. alarm opens the session and rings once
		p = pair(5); a = p[0]; b = p[1];
		a.alarm(); run(a, b, 40000);
		check("alarm received once", b.alarms == 1);
		check("alarm opened the session", b.logic.state() == SessionLogic.State.SESSION);

		// 6. both write at the same moment while asleep
		p = pair(6); a = p[0]; b = p[1];
		a.say("de A"); b.say("de B");
		run(a, b, 3 * 60000);
		check("simultaneous opening: both messages arrive", count(b.chat, "< de A") == 1 && count(a.chat, "< de B") == 1);

		// 7. robustness: random sleep phases and 10% frame loss, 200 runs
		int ok = 0, dup = 0, runs = 200;
		for (int s = 0; s < runs; s++) {
			p = pair(100 + s); a = p[0]; b = p[1];
			a.rxLoss = Double.parseDouble(System.getProperty("loss","0.1")); b.rxLoss = a.rxLoss;
			a.say("mensaje " + s); run(a, b, 3 * 60000);
			int n = count(b.chat, "< mensaje " + s);
			if (n >= 1 && a.logic.state() == SessionLogic.State.SESSION) ok++;
			if (n > 1) dup++;
			if (n == 0 || a.logic.state() != SessionLogic.State.SESSION) System.out.println("     run " + s + ": A " + a.logic.state() + " " + a.events + " | B " + b.logic.state() + " " + b.chat);
		}
		System.out.printf("     random phase + 10%% loss: %d/%d opened and delivered, %d duplicates%n", ok, runs, dup);
		check("random phase + 10% loss: >= 98% delivered, no duplicates", ok >= runs * 0.98 && dup == 0);

		// 8. battery: share of time awake while idle (no traffic) for one hour
		p = pair(7); a = p[0];
		long before = a.awakeMs; run(a, p[1], 3600000);
		double duty = (a.awakeMs - before) / 3600000.0;
		System.out.printf("     idle hour: awake %.1f%% of the time (check window only)%n", duty * 100);
		check("idle duty cycle about 1 s every 15 s", duty > 0.05 && duty < 0.08);

		System.out.println(fails == 0 ? "SessionSim: all passed" : "SessionSim: " + fails + " FAILED");
		System.exit(fails == 0 ? 0 : 1);
	}
}
