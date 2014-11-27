package de.egh.dynamodrivenodometer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manage message in a closed buffer with up to 3 different messages. If a same
 * message will be added a second time, just the timestamp will be updated. When
 * the 4th message will be added, the oldest one will be removed. Created by
 * ChristianSchulzendor on 10.08.2014.
 */
public class MessageBox {

	private class MsgComparator implements Comparator<Msg> {

		@Override
		public int compare(Msg msg1, Msg msg2) {
			if (msg1.timestamp == msg2.timestamp)
				return 0;
			return (msg1.timestamp < msg2.timestamp) ? 1 : -1;
		}
	}

	public class Msg {
		public long getTimestamp() {
			return timestamp;
		}

		public String getText() {
			return text;
		}

		private long timestamp;
		private String text;

		private Msg(String text) {
			this.text = text;
			this.timestamp = System.currentTimeMillis();
		}

		private Msg(String text, long timestamp) {
			this.text = text;
			this.timestamp = timestamp;
		}

	}

	/**
	 * Writes all message into a String. The string can be used to initilize the
	 * Box from a simple persistance.
	 */
	public String asString() {
		String s = "";

		for (Msg msg : getSorted()) {
			String line = msg.timestamp + " " + msg.text + "\n";
			s += line;
		}

		return s;
	}

	// Number of different messages that can be stored.
	private static final int SIZE = 3;

	// Up to 3 error dodMessages from the device in the format <timestamp>
	// <message>
	private List<Msg> messageList = new ArrayList<Msg>();
	private Map<String, Msg> map = new HashMap<String, Msg>();

	// Initialize from Shared Preferences
	public MessageBox(String asOneString) {
		if (asOneString != null && asOneString.length() > 0) {
			String[] lines = asOneString.split("\\n");
			for (String line : lines) {
				try {
					String num = line.split(" ")[0];
					String text = line.split("^\\d+ ")[1].trim();
					long l = Long.valueOf(num);
					map.put(text, new Msg(text, l));
				} catch (Exception e) {
					// Illegal messages will be ignored, e.g. empty message text
				}
			}
		}

	}

	/** Returns all Messages as list, sorted by timestamp descending. */
	public List<Msg> getSorted() {
		List<Msg> list = new ArrayList<Msg>();

		for (String key : map.keySet()) {
			list.add(map.get(key));
		}

		Collections.sort(list, new MsgComparator());

		return list;
	}

	/**
	 * Method for testing, use add(text) instead. Creates a message for a
	 * timestamp and add this to the log. The log has only up to 3 entries, so
	 * older one will be deleted.
	 */
	private void add(String text, long timestamp) {
		// Lookpup for the same message and just update timestamp
		if (map.containsKey(text))
			map.get(text).timestamp = timestamp;

		// If Box is full, remove oldest one
		if (map.size() > SIZE) {
			Msg oldest = null;
			for (String key : map.keySet()) {
				if (oldest == null || map.get(key).timestamp < oldest.timestamp)
					oldest = map.get(key);
			}
			map.remove(oldest.text);
		}

		// Now new message can be added
		map.put(text, new Msg(text));

	}

	/**
	 * Creates a message with timestamp and add this to the log. The log has
	 * only up to 3 entries, so older one will be deleted.
	 */
	public void add(String text) {
		if (text != null && text.length() > 0)
			add(text, System.currentTimeMillis());
	}
}