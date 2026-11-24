package com.fcribbit.ribbit;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Chat history (the web app keeps it in IndexedDB "ribbit"/"messages"). */
public final class MessageStore extends SQLiteOpenHelper {
	private static final String TABLE = "messages";
	private static final int KEEP = 500;

	public MessageStore(Context context) {
		super(context, "ribbit.db", null, 1);
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE " + TABLE + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
				+ "time INTEGER NOT NULL, type INTEGER NOT NULL, sender TEXT, body TEXT, encrypted INTEGER)");
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
	}

	public void add(ChatMessage m) {
		ContentValues v = new ContentValues();
		v.put("time", m.time);
		v.put("type", m.type);
		v.put("sender", m.sender);
		v.put("body", m.body);
		v.put("encrypted", m.encrypted ? 1 : 0);
		try {
			m.id = getWritableDatabase().insert(TABLE, null, v);
		} catch (Exception e) {
			// storage problems must not break the chat
		}
	}

	public List<ChatMessage> loadRecent() {
		List<ChatMessage> list = new ArrayList<ChatMessage>();
		Cursor c = null;
		try {
			c = getReadableDatabase().query(TABLE, new String[] { "_id", "time", "type", "sender", "body", "encrypted" },
					null, null, null, null, "_id DESC", String.valueOf(KEEP));
			while (c.moveToNext()) {
				ChatMessage m = new ChatMessage(c.getLong(1), c.getInt(2), c.getString(3), c.getString(4), c.getInt(5) != 0);
				m.id = c.getLong(0);
				list.add(m);
			}
		} catch (Exception e) {
			// ignore
		} finally {
			if (c != null)
				c.close();
		}
		Collections.reverse(list);
		return list;
	}

	public void clear() {
		try {
			getWritableDatabase().delete(TABLE, null, null);
		} catch (Exception e) {
			// ignore
		}
	}
}
