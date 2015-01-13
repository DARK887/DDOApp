package de.egh.dynamodrivenodometer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manage message in a closed buffer with up to 3 different messages. If a same message will be
 * added a second time, just the timestamp will be updated. When the 4th message will be added,
 * the oldest one will be removed.
 * Created by ChristianSchulzendor on 10.08.2014.
 */
public class MessageBox {

	private class MsgComparator implements Comparator<Msg> {

		@Override
		public int compare(Msg msg1, Msg msg2) {
			return (msg1.timestamp < msg2.timestamp)? -1:1;
		}
	}

	private class Msg {
		long timestamp;
		String text;

		Msg(String text){
			this.text = text;
			this.timestamp = System.currentTimeMillis();
		}

		Msg(String text, long timestamp){
			this.text = text;
			this.timestamp = timestamp;
		}


	}



	//Number of different messages that can be stored.
	private static final int SIZE = 3;

	//Up to 3 error dodMessages from the device in the format <timestamp> <message>
	private List<Msg> messageList = new ArrayList<Msg>();
	private Map<String, Msg> map = new HashMap<String,Msg>();

	//Initialize from Shared Preferences
	MessageBox(String asOneString) {
		if (asOneString != null) {
			String[] lines = asOneString.split("\\n");
			for (String line : lines) {
        String[] items = line.split("\\|");
				map.put(items[1], new Msg(items[1], Long.getLong(items[0])));
			}
		}

	}

	/**Returns all Messages as list, sorted by timestamp descending.*/
	public List<Msg> getSorted(){
		List<Msg> list = new ArrayList<Msg>();

		for(String key: map.keySet()){
			list.add(map.get(key));
		}

		Collections.sort(list, new MsgComparator());

		return list;
	}

	/**
	 * Creates a message with timestamp and add this to the log.
	 * The log has only up to 3 entries, so older one will be deleted.
	 */
	void add(String text) {

		//Lookpup for the same message and just update timestamp
		if(map.containsKey(text))
			map.get(text).timestamp = System.currentTimeMillis();

		//If Box is full, remove oldest one
    if(map.size() >=SIZE){
	    Msg oldest = null;
	    for(String key:map.keySet()){
       if(oldest == null|| map.get(key).timestamp < oldest.timestamp)
	       oldest = map.get(key);
	    }
	    map.remove(oldest.text);
    }

		// Now new message can be added
		map.put(text, new Msg(text));

//		for (Msg msg : map.containsKey()) {
//			if (msg.equals(text)) {
//				msg.timestamp = System.currentTimeMillis();
//				return;
//			}
//		}
//		if (messageList.size() >= SIZE) {
//			Msg oldestMsg = messageList.get(0);
//			for (int i = 1; i<messageList.size(); i++) {
//          if(messageList.get(i).timestamp < oldestMsg.timestamp)
//	          oldestMsg = messageList.get(i);
//			}
//
//		}


//		if (messageList.size() >= SIZE) {
//			int last = messageList.size() - 1;
//			for (int i = messageList.size() - 1; i > 0; i++) {
//				messageList.set(i - 1, messageList.get(i));
//			}
//			messageList.remove(last);
//		}
//		messageList.add(sdf.format(new Date()) + " " + text);
	}

}
