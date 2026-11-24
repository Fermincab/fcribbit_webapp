package com.fcribbit.ribbit;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class MessageAdapter extends BaseAdapter {
	private final List<ChatMessage> items = new ArrayList<ChatMessage>();
	private final LayoutInflater inflater;
	private final Drawable lock;
	private final DateFormat timeFormat;
	private final DateFormat dateFormat;
	private final String me;
	private boolean hideSenders;

	public MessageAdapter(Context context) {
		inflater = LayoutInflater.from(context);
		timeFormat = android.text.format.DateFormat.getTimeFormat(context);
		dateFormat = android.text.format.DateFormat.getDateFormat(context);
		me = context.getString(R.string.me);
		lock = context.getResources().getDrawable(android.R.drawable.ic_lock_lock);
		int size = (int) (14 * context.getResources().getDisplayMetrics().density + 0.5f);
		lock.setBounds(0, 0, size, size);
	}

	public void setHideSenders(boolean hide) {
		if (hide != hideSenders) {
			hideSenders = hide;
			notifyDataSetChanged();
		}
	}

	public void setAll(List<ChatMessage> list) {
		items.clear();
		items.addAll(list);
		notifyDataSetChanged();
	}

	public void add(ChatMessage m) {
		items.add(m);
		notifyDataSetChanged();
	}

	public void clear() {
		items.clear();
		notifyDataSetChanged();
	}

	@Override
	public int getCount() {
		return items.size();
	}

	@Override
	public Object getItem(int position) {
		return items.get(position);
	}

	@Override
	public long getItemId(int position) {
		return position;
	}

	@Override
	public boolean isEnabled(int position) {
		return false;
	}

	@Override
	public View getView(int position, View view, ViewGroup parent) {
		if (view == null)
			view = inflater.inflate(R.layout.item_message, parent, false);
		ChatMessage m = items.get(position);
		LinearLayout bubble = (LinearLayout) view.findViewById(R.id.bubble);
		TextView meta = (TextView) view.findViewById(R.id.meta);
		TextView body = (TextView) view.findViewById(R.id.body);
		LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bubble.getLayoutParams();

		Date when = new Date(m.time);
		String time = timeFormat.format(when);
		if (System.currentTimeMillis() - m.time > 20 * 3600 * 1000L)
			time = dateFormat.format(when) + " " + time;

		if (m.type == ChatMessage.ALERT) {
			lp.gravity = Gravity.CENTER_HORIZONTAL;
			bubble.setBackgroundDrawable(null);
			meta.setVisibility(View.GONE);
			body.setTextColor(0xFF9AA59A);
			body.setTextSize(12);
		} else {
			boolean out = m.type == ChatMessage.OUT;
			lp.gravity = out ? Gravity.RIGHT : Gravity.LEFT;
			bubble.setBackgroundResource(out ? R.drawable.bubble_out : R.drawable.bubble_in);
			body.setTextColor(0xFFFFFFFF);
			body.setTextSize(14);
			if (hideSenders) {
				meta.setVisibility(View.GONE);
			} else {
				String who = Payload.describeSender(m.sender);
				if (who.length() == 0)
					who = out ? me : "?";
				meta.setText(who + "  " + time);
				meta.setVisibility(View.VISIBLE);
			}
		}
		bubble.setLayoutParams(lp);
		body.setText(m.body);
		body.setCompoundDrawables(m.encrypted ? lock : null, null, null, null);
		return view;
	}
}
