package com.fcribbit.ribbit;

public final class ChatMessage {
	public static final int IN = 0;
	public static final int OUT = 1;
	public static final int ALERT = 2;

	public long id = -1;
	public final long time;
	public final int type;
	public final String sender; // raw header "name|callsign|grid|phone"
	public final String body;
	public final boolean encrypted;

	public ChatMessage(long time, int type, String sender, String body, boolean encrypted) {
		this.time = time;
		this.type = type;
		this.sender = sender == null ? "" : sender;
		this.body = body == null ? "" : body;
		this.encrypted = encrypted;
	}
}
