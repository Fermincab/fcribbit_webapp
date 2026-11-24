package com.fcribbit.ribbit;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Session protocol between two Ribbit phones that sleep most of the time.
 * Plain Java with an injectable clock, so it can be simulated on a desktop.
 *
 * - SLEEP: the phone only wakes up briefly every few seconds to look for the wake tone.
 * - Sending while there is no session: the first frame goes out preceded by the wake
 *   tone (state OPENING) and is repeated, tone included, until the other phone confirms
 *   with CTL1:SESSION_OK (at most MAX_ATTEMPTS times). Frames sent meanwhile wait in a queue.
 * - A phone that heard the tone (WOKEN) and then a frame opens the session and confirms.
 * - Every frame sent or received extends the session. When it runs out, the phone that
 *   opened it sends CTL1:SESSION_END; the other one sends it after a grace period if it
 *   didn't hear one. Both go back to SLEEP; the next message brings the tone back.
 */
public final class SessionLogic {
	public enum State { SLEEP, WOKEN, OPENING, SESSION }

	public enum Event { OPENING, RETRY, NO_RESPONSE, OPENED, CLOSING_SOON, CLOSED, QUEUED }

	public static final String CONTROL = "CTL1:";
	public static final String SESSION_OK = "SESSION_OK";
	public static final String SESSION_END = "SESSION_END";
	public static final String ALARM = "ALARM";

	public interface Clock {
		long now();
	}

	public interface Actions {
		/** Start transmitting; call onTxDone() when finished. toneMs = 0 for no wake tone. */
		void transmit(Outgoing o, int toneMs);

		/** Show/store a chat message (sent or received, never control frames). */
		void deliver(ChatMessage m);

		/** A received CTL1:ALARM. */
		void alarm(String sender);

		void event(Event e, int detail);

		/** The state changed: listening mode, wake locks and notifications may need updating. */
		void stateChanged();

		/** false while transmissions must wait */
		boolean canTransmit();
	}

	/** A frame to send. display is shown in the chat when it first goes out (null for control frames). */
	public static final class Outgoing {
		public final byte[] payload;
		public final ChatMessage display;
		boolean shown;

		public Outgoing(byte[] payload, ChatMessage display) {
			this.payload = payload;
			this.display = display;
		}
	}

	// timing (ms); the tone length comes from the check interval
	public long sessionMs = 5 * 60 * 1000;
	public int toneMs = 18000;
	public static final long ACK_TIMEOUT = 15000;   // wait for SESSION_OK after our tone + frame
	public static final int RETRY_JITTER = 15000;   // random extra wait, so two phones opening at once stop colliding
	public static final int MAX_ATTEMPTS = 3;
	public static final long WOKEN_TIMEOUT = 45000; // after hearing a tone, wait this long for a frame
	public static final long RX_GUARD = 2000;       // the other radio's VOX is still keyed this long after its frame
	public static final long CLOSE_GRACE = 15000;
	public static final long WARN_BEFORE = 30000;
	public static final long TONE_MEMORY = 60000;   // a frame this soon after a tone came from a waking peer

	private final Clock clock;
	private final Actions actions;
	private final HeaderSource headers;

	private State state = State.SLEEP;
	private boolean initiator;
	private long expiry;
	private boolean warned;
	private long wokenDeadline;
	private long ackDeadline;
	private int attempts;
	private Outgoing opening;
	private final List<Outgoing> waiting = new ArrayList<Outgoing>(); // until the session is open
	private final List<Outgoing> txQueue = new ArrayList<Outgoing>();
	private Outgoing current;
	private boolean currentHasTone;
	private boolean closeSent;
	private long lastRxAt = -RX_GUARD;
	private long toneHeardAt = -TONE_MEMORY - 1;
	private String lastKey;
	private Random random = new Random();

	public SessionLogic(Clock clock, Actions actions, HeaderSource headers) {
		this.clock = clock;
		this.actions = actions;
		this.headers = headers;
	}

	/** where control frames get their header from (the station settings) */
	public interface HeaderSource {
		String header();
	}

	/** for reproducible simulations */
	public void setRandomSeed(long seed) {
		random = new Random(seed);
	}

	public State state() {
		return state;
	}

	public boolean isInitiator() {
		return initiator;
	}

	public int attempts() {
		return attempts;
	}

	public boolean transmitting() {
		return current != null;
	}

	public boolean transmittingTone() {
		return current != null && currentHasTone;
	}

	public long remainingMs() {
		return state == State.SESSION ? Math.max(0, expiry - clock.now()) : 0;
	}

	/** true when the phone must listen continuously (and get tick() calls) */
	public boolean busy() {
		return state != State.SLEEP || current != null || !txQueue.isEmpty();
	}

	// ------------------------------------------------------------ sending

	/** a chat message or an alarm from the user */
	public void send(byte[] payload, ChatMessage display) {
		Outgoing o = new Outgoing(payload, display);
		long now = clock.now();
		switch (state) {
			case SESSION:
				extend(now);
				queue(o);
				break;
			case OPENING:
			case WOKEN:
				// WOKEN: the other phone is opening a session right now; going on air would collide
				waiting.add(o);
				actions.event(Event.QUEUED, waiting.size());
				break;
			default:
				state = State.OPENING;
				initiator = true;
				attempts = 1;
				opening = o;
				ackDeadline = 0;
				actions.event(Event.OPENING, attempts);
				queue(o);
				actions.stateChanged();
		}
		pump();
	}

	private void queue(Outgoing o) {
		txQueue.add(o);
	}

	private void sendControl(String what) {
		queue(new Outgoing(Payload.pack(headers.header(), CONTROL + what), null));
	}

	private void pump() {
		if (current != null || txQueue.isEmpty())
			return;
		long now = clock.now();
		if (now < lastRxAt + RX_GUARD || !actions.canTransmit())
			return; // tick() tries again
		current = txQueue.remove(0);
		currentHasTone = current == opening && state == State.OPENING;
		if (current.display != null && !current.shown) {
			current.shown = true;
			actions.deliver(current.display);
		}
		actions.transmit(current, currentHasTone ? toneMs : 0);
	}

	public void onTxDone() {
		Outgoing done = current;
		current = null;
		long now = clock.now();
		if (done != null && done == opening && state == State.OPENING)
			ackDeadline = now + ACK_TIMEOUT + random.nextInt(RETRY_JITTER);
		if (state == State.SESSION && !closeSent)
			extend(now);
		if (closeSent && txQueue.isEmpty()) {
			goToSleep();
			return;
		}
		pump();
		actions.stateChanged();
	}

	// ----------------------------------------------------------- receiving

	public void onToneHeard() {
		long now = clock.now();
		toneHeardAt = now;
		if (state == State.OPENING && current == null) {
			// the other phone is opening too (our tones collided): let it, and send ours once it's open
			txQueue.remove(opening);
			waiting.add(0, opening);
			opening = null;
			ackDeadline = 0;
			initiator = false;
			state = State.WOKEN;
			wokenDeadline = now + WOKEN_TIMEOUT;
			actions.stateChanged();
		} else if (state == State.SLEEP) {
			state = State.WOKEN;
			wokenDeadline = now + WOKEN_TIMEOUT;
			actions.stateChanged();
		} else if (state == State.WOKEN) {
			// a repeated call (we missed its frame): stay awake for this one too
			wokenDeadline = now + WOKEN_TIMEOUT;
		}
	}

	/** a decoded frame (Payload.parse result) */
	public void onFrame(String sender, String message) {
		long now = clock.now();
		lastRxAt = now;
		if (message == null)
			message = "";
		if (message.startsWith(CONTROL)) {
			String what = message.substring(CONTROL.length()).trim();
			if (what.equals(SESSION_OK)) {
				if (state == State.OPENING)
					open(true, now);
				else if (state == State.SESSION)
					extend(now);
				pump();
				return;
			}
			if (what.equals(SESSION_END)) {
				if (state == State.SESSION || state == State.WOKEN) {
					txQueue.clear();
					goToSleep();
				}
				return;
			}
		}
		boolean toneRecent = now - toneHeardAt <= TONE_MEMORY;
		String key = sender + "\n" + message;
		// a repeat of the opening frame: the other phone missed our SESSION_OK
		boolean repeat = toneRecent && state == State.SESSION && !initiator && key.equals(lastKey);
		lastKey = key;
		if (!repeat) {
			if (message.equals(CONTROL + ALARM))
				actions.alarm(sender);
			else
				actions.deliver(new ChatMessage(System.currentTimeMillis(), ChatMessage.IN, sender, message, false));
		}
		switch (state) {
			case SESSION:
				extend(now);
				if (repeat)
					sendControl(SESSION_OK);
				break;
			case OPENING:
				// both opened at once: the other side is clearly awake
				open(true, now);
				break;
			case WOKEN:
				open(false, now);
				break;
			default:
				if (toneRecent)
					open(false, now);
				// otherwise: someone without a session (e.g. the web app) while we happen to listen
		}
		pump();
	}

	private void open(boolean asInitiator, long now) {
		boolean confirm = !asInitiator;
		state = State.SESSION;
		initiator = asInitiator;
		closeSent = false;
		ackDeadline = 0;
		opening = null;
		extend(now);
		if (confirm)
			sendControl(SESSION_OK);
		txQueue.addAll(waiting);
		waiting.clear();
		actions.event(Event.OPENED, 0);
		actions.stateChanged();
	}

	private void extend(long now) {
		expiry = now + sessionMs;
		warned = false;
	}

	private void goToSleep() {
		boolean wasSession = state == State.SESSION;
		state = State.SLEEP;
		initiator = false;
		closeSent = false;
		opening = null;
		ackDeadline = 0;
		waiting.clear();
		if (wasSession)
			actions.event(Event.CLOSED, 0);
		actions.stateChanged();
	}

	// ---------------------------------------------------------------- time

	/** call about twice a second while busy() */
	public void tick() {
		long now = clock.now();
		switch (state) {
			case WOKEN:
				if (now >= wokenDeadline && current == null) {
					state = State.SLEEP;
					if (!waiting.isEmpty()) {
						// we were woken but nothing came: send what the user wrote, opening our own session
						Outgoing first = waiting.remove(0);
						List<Outgoing> rest = new ArrayList<Outgoing>(waiting);
						waiting.clear();
						send(first.payload, first.display);
						waiting.addAll(rest);
					}
					actions.stateChanged();
				}
				break;
			case OPENING:
				if (ackDeadline != 0 && now >= ackDeadline && current == null) {
					if (attempts < MAX_ATTEMPTS) {
						++attempts;
						ackDeadline = 0;
						actions.event(Event.RETRY, attempts);
						queue(opening);
					} else {
						actions.event(Event.NO_RESPONSE, attempts);
						txQueue.clear();
						goToSleep();
					}
				}
				break;
			case SESSION:
				if (closeSent)
					break;
				if (!warned && now >= expiry - WARN_BEFORE) {
					warned = true;
					actions.event(Event.CLOSING_SOON, (int) ((expiry - now) / 1000));
				}
				long limit = initiator ? expiry : expiry + CLOSE_GRACE;
				if (now >= limit && current == null && txQueue.isEmpty()) {
					closeSent = true;
					sendControl(SESSION_END);
				}
				break;
			default:
		}
		pump();
	}
}
