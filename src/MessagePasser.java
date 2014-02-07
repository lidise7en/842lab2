/* 18-842 Distributed Systems
 * Lab 0
 * Group 41 - ajaltade & dil1
 */

/*
 * TODO: Figure out what seq nums to nack - iterate holdback
 * 		 Parsing
 * 		 Thread to send pulse check
 * 		 
 */

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

public class MessagePasser {

	// store the delayed send msg
	private LinkedList<DelayedMessage> delaySendQueue = new LinkedList<DelayedMessage>(); 
	// store the delayed recv msg
	private LinkedList<Message> delayRecvQueue = new LinkedList<Message>(); 
	// store all the received msg from all receive sockets
	private LinkedList<Message> recvQueue = new LinkedList<Message>(); 
	private HashMap<String, ObjectOutputStream> outputStreamMap = new HashMap<String, ObjectOutputStream>();
	private Map<SocketInfo, Socket> sockets = new HashMap<SocketInfo, Socket>();
	private Map<SrcGroup, List<Message>> holdBackMap = new HashMap<SrcGroup, List<Message>>();
	private Map<NackItem, Message> allMsg = new HashMap<NackItem, Message>();

	private String configFilename;

	private String localName;
	private String loggerName = "logger";
	private ServerSocket hostListenSocket;
	private SocketInfo hostSocketInfo;
	private Config config;
	private static int currSeqNum;

	private ClockService clockSer;
	// map of SEEN seqNums
	private Map<SrcGroup, Integer> seqNums = new HashMap<SrcGroup, Integer>(); 

	private enum RuleType {
		SEND, RECEIVE,
	}

	/*
	 * sub-class for listen threads
	 */

	public class startListen extends Thread {

		public startListen() {

		}

		public void run() {
			System.out.println("Running");
			try {
				while (true) {
					Socket sock = hostListenSocket.accept();
					new ListenThread(sock).start();
				}
			} catch (IOException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
	
	public class sendPeriodNACK extends Thread {
		public sendPeriodNACK() {}
		
		public void run() {
			while(true) {			
				sendNACK();
				try {
					Thread.currentThread().sleep(5000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}
	public class ListenThread extends Thread {
		private Socket LisSock = null;

		public ListenThread(Socket sock) {
			this.LisSock = sock;
		}

		public void run() {

			try {
				ObjectInputStream in = new ObjectInputStream(
						this.LisSock.getInputStream());

				while (true) {
					TimeStampedMessage msg = (TimeStampedMessage) in
							.readObject();

					if (msg.getKind().equalsIgnoreCase("NACK")) {
						getNACK(msg);
					} else {
						if (msg.getKind().equalsIgnoreCase("NACK REPLY")) {
							msg = (TimeStampedMessage) msg.getData();
						}

						// msg.dumpMsg();
						parseConfig();
						Rule rule = null;
						if ((rule = matchRule(msg, RuleType.RECEIVE)) != null) {
							if (rule.getAction().equals("drop")) {
								synchronized (delayRecvQueue) {
									while (!delayRecvQueue.isEmpty()) {
										checkAdd(delayRecvQueue.pollLast());
									}
								}
								continue;
							} else if (rule.getAction().equals("duplicate")) {
								System.out.println("Duplicating message");
								synchronized (recvQueue) {
									checkAdd(msg);
									checkAdd(msg.makeCopy());

									synchronized (delayRecvQueue) {
										while (!delayRecvQueue.isEmpty()) {
											checkAdd(delayRecvQueue.pollLast());
										}
									}
								}
							} else if (rule.getAction().equals("delay")) {
								synchronized (delayRecvQueue) {
									delayRecvQueue.add(msg);
								}
							} else {
								System.out.println("We receive a wierd msg!");
							}
						} else {
							synchronized (recvQueue) {
								addToRecvQueue(recvQueue, msg);
								synchronized (delayRecvQueue) {
									while (!delayRecvQueue.isEmpty()) {
										checkAdd(delayRecvQueue.pollLast());
									}
								}
							}
						}

					}
				}
			} catch (EOFException e2) {
				System.out.println("A peer disconnected");
				for (Map.Entry<SocketInfo, Socket> entry : sockets.entrySet()) {
					if (this.LisSock.getRemoteSocketAddress().equals(
							entry.getValue().getLocalSocketAddress())) {
						System.out.println("Lost connection to "
								+ entry.getKey().getName());
						try {
							ObjectOutputStream out = outputStreamMap.get(entry
									.getKey().getName());
							outputStreamMap.remove(entry.getKey().getName());
							out.close();

							sockets.remove(entry.getKey());
							entry.getValue().close();
						} catch (IOException e) {
							// Auto-generated catch block
							e.printStackTrace();
						}
						break;
					}
				}

			} catch (IOException e1) {
				// Auto-generated catch block
				e1.printStackTrace();
			} catch (ClassNotFoundException e) {
				// Auto-generated catch block
				e.printStackTrace();
			}

		}

	}

	public MessagePasser(String configuration_filename, String local_name) {
		configFilename = configuration_filename;
		localName = local_name;
		currSeqNum = 1;
		try {
			parseConfig();
		} catch (FileNotFoundException e) {
			// Auto-generated catch block
			e.printStackTrace();
		}

		/*debug for parsing yaml to see whether we get the correct groups */
		for(Group g : this.config.getGroupList()) {
			System.out.println(g.toString());
		}
		/* end debug */
		/* Now, using localName get *this* MessagePasser's SocketInfo and
		 * setup the listening socket and all other sockets to other hosts.
		 * 
		 * We can optionally, save this info in hostSocket and hostSocketInfo to
		 * avoid multiple lookups into the 'sockets' Map.
		 */

		/* for clockService */
		if (this.config.isLogical == true) {
			this.clockSer = new LogicalClockService(new TimeStamp());
		} else {
			this.clockSer = new VectorClockService(new TimeStamp());
		}

		if (!this.config.isLogical) {
			HashMap<String, Integer> map = this.clockSer.getTs()
					.getVectorClock();
			for (SocketInfo e : this.config.configuration) {
				map.put(e.getName(), 0);
			}
		}

		/* */
		hostSocketInfo = config.getConfigSockInfo(localName);
		if (hostSocketInfo == null) {
			/*** ERROR ***/
			System.out.println("The local name is not correct.");
			System.exit(0);
		} else {
			/* Set up socket */
			System.out.println("For this host: " + hostSocketInfo.toString());
			try {
				hostListenSocket = new ServerSocket(hostSocketInfo.getPort(),
						10, InetAddress.getByName(hostSocketInfo.getIp()));
			} catch (IOException e) {
				/*** ERROR ***/
				System.out.println("Cannot start listen on socket. "
						+ e.toString());
				System.exit(0);
			}
			/*need to initiate the seqNums map */
			for(Group g : this.config.getGroupList()) {
				SrcGroup tmp = new SrcGroup(this.localName, g.getGroupName());
				this.seqNums.put(tmp, 0);
				if(g.getMemberList().contains(this.localName)) {
					for(SocketInfo s : this.config.configuration) {
						SrcGroup tmpArray = new SrcGroup(s.getName(), g.getGroupName());
						this.seqNums.put(tmpArray, 0);
					}
				}
			}
			
			/* start the listen thread */
			new startListen().start();

		}
	}
	public void startCheckingThread() {
		/*start the thread sending periodicall NACK */
		new sendPeriodNACK().start();
	}
	public void send(Message message) {
		/*
		 * Re-parse the config. Check message against sendRules. Finally, send
		 * the message using sockets.
		 */
		/* update the timestamp */
		this.clockSer.addTS(this.localName);
		((TimeStampedMessage) message).setMsgTS(this.clockSer.getTs()
				.makeCopy());
		System.out.println("TS add by 1");
		checkSend(message);
		
		
	} 

	private void checkSend(Message message) {
		// check if multicast
		if (config.getGroup(message.getDest()) != null) { // multicast message
			Group sendGroup = config.getGroup(message.getDest());
			SrcGroup srcGrp = new SrcGroup(localName, sendGroup.getGroupName());
			updateSequenceNumber(srcGrp); // on send, should update first
			int sNum = seqNums.get(srcGrp);
			// change to update function
			NackItem ni = new NackItem(srcGrp, sNum);
			allMsg.put(ni, message);
			((TimeStampedMessage) message).setGrpSeqNum(sNum);
			for (String member : sendGroup.getMemberList()) {
				applyRulesSend(message, member);
			}

		} else { // regular message
			applyRulesSend(message, message.getDest());
		}
	}
	
	private void applyRulesSend(Message message, String dest){
		try {
			parseConfig();
		} catch (FileNotFoundException e) {
			// Auto-generated catch block
			e.printStackTrace();
		}
		message.set_source(localName);
		message.set_seqNum(currSeqNum++);

		Rule rule = null;
		if ((rule = matchRule(message, RuleType.SEND)) != null) {
			if (rule.getAction().equals("drop")) {
				return;
			} else if (rule.getAction().equals("duplicate")) {
				Message dupMsg = message.makeCopy();
				dupMsg.set_duplicate(true);

				/* Send 'message' and 'dupMsg' */
				checkSend(message);
				((TimeStampedMessage) dupMsg).setMsgTS(this.clockSer.getTs()
						.makeCopy());
				doSend(dupMsg,dest);

				/*
				 * We need to send delayed messages after new message. This was
				 * clarified in Live session by Professor.
				 */
				for (DelayedMessage dm : delaySendQueue) {
					doSend(dm.getMessage(),dm.getDest());
				}
				delaySendQueue.clear();

			} else if (rule.getAction().equals("delay")) {
				DelayedMessage dm = new DelayedMessage(message,dest);
				delaySendQueue.add(dm);
			} else {
				System.out.println("We get a wierd message here!");
			}
		} else {
			doSend(message,dest);

			/*
			 * We need to send delayed messages after new message. This was
			 * clarified in Live session by Professor.
			 */
			for (DelayedMessage dm : delaySendQueue) {
				doSend(dm.getMessage(),dm.getDest());
			}
			delaySendQueue.clear();
		}
	}

	private void doSend(Message message, String dest) {

		TimeStampedMessage msg = (TimeStampedMessage) message;

		/* end fill */
		Socket sendSock = null;
		for (SocketInfo inf : sockets.keySet()) {
			if (inf.getName().equals(dest)) {
				sendSock = sockets.get(inf);
				break;
			}
		}
		if (sendSock == null) {
			try {
				SocketInfo inf = config.getConfigSockInfo(dest);
				if (inf == null) {
					System.out.println("Cannot find config for " + dest);
					return;
				}
				sendSock = new Socket(inf.getIp(), inf.getPort());
			} catch (ConnectException e2) {
				System.out.println("Connection refused to " + dest);
				return;
			} catch (IOException e) {
				// Auto-generated catch block
				e.printStackTrace();
				return;
			}
			sockets.put(config.getConfigSockInfo(dest), sendSock);
			try {
				outputStreamMap.put(dest,
						new ObjectOutputStream(sendSock.getOutputStream()));
			} catch (IOException e) {
				// Auto-generated catch block
				e.printStackTrace();
			}
		}

		ObjectOutputStream out;
		try {
			out = outputStreamMap.get(dest);
			System.out.println("msgTS in doSend" + msg.getMsgTS().toString());
			out.writeObject(msg);
			out.flush();

		} catch (SocketException e1) {
			System.out.println("Peer " + dest + " is offline. Cannot send");

		} catch (IOException e) {
			// Auto-generated catch block
			e.printStackTrace();
		}
	}

	public Message receive() {
		/*
		 * Re-parse the config. Receive the message using sockets. Finally,
		 * check message against receiveRules.
		 */

		synchronized (recvQueue) {
			if (!recvQueue.isEmpty()) {
				Message popMsg = recvQueue.remove();
				/* add ClockService */
				TimeStampedMessage msg = (TimeStampedMessage) popMsg;
				// System.out.println("new Debug sentence");
				// msg.dumpMsg();
				this.clockSer.updateTS(msg.getMsgTS());
				this.clockSer.addTS(this.localName);
				/* */

				return popMsg;
			}
		}

		return null;
	}

	public Message receiveLogger() {
		/*
		 * Re-parse the config. Receive the message using sockets. Finally,
		 * check message against receiveRules.
		 */

		synchronized (recvQueue) {
			if (!recvQueue.isEmpty()) {
				Message popMsg = recvQueue.remove();
				/* add ClockService */
				TimeStampedMessage msg = (TimeStampedMessage) popMsg;
				// System.out.println("new Debug sentence");
				// msg.dumpMsg();
				// this.clockSer.updateTS(msg.getMsgTS());
				// this.clockSer.addTS(this.localName);
				/* */

				return popMsg;
			}
		}

		return null;
	}

	/*
	 * public void logEvent(String msg, TimeStamp ts) { TimeStampedMessage
	 * newTsMsg; try { parseConfig(); } catch (FileNotFoundException e) {
	 * System.out.println(
	 * "[LOG_EVENT]: reading config file failed, continuing with existing config"
	 * ); } System.out.println("TS entered into logEvent" + ts.toString());
	 * newTsMsg = new TimeStampedMessage(loggerName, "log", msg, ts);
	 * this.send(newTsMsg); }
	 */
	public Rule matchRule(Message message, RuleType type) {
		List<Rule> rules = null;

		if (type == RuleType.SEND) {
			rules = config.getSendRules();
		} else {
			rules = config.getReceiveRules();
		}

		if (rules == null) {
			return null;
		}

		for (Rule r : rules) {
			if (!r.getSrc().isEmpty()) {
				if (!message.getSrc().equals(r.getSrc())) {
					continue;
				}
			}

			if (!r.getDest().isEmpty()) {
				if (!message.getDest().equals(r.getDest())) {
					continue;
				}
			}

			if (!r.getKind().isEmpty()) {
				if (!message.getKind().equals(r.getKind())) {
					continue;
				}

			}

			if (r.getSeqNum() != -1) {
				if (message.getSeqNum() != r.getSeqNum()) {
					continue;
				}
			}

			if (!r.getDuplicate().isEmpty()) {
				if (!(message.isDuplicate() == true
						&& r.getDuplicate().equals("true") || message
						.isDuplicate() == false
						&& r.getDuplicate().equals("false"))) {
					continue;
				}
			}

			return r;
		}
		return null;
	}

	private void parseConfig() throws FileNotFoundException {
	    InputStream input = new FileInputStream(new File(configFilename));
        Constructor constructor = new Constructor(Config.class);
        SocketInfo mySocketInfo;
	    Yaml yaml = new Yaml(constructor);
	    
	    /* SnakeYAML will parse and populate the Config object for us */
	    config = (Config) yaml.load(input);
	    /*some tricky hack to take care of Groups */
	    for(Group g : this.config.getGroupList()) {
	    	List<String> tmpList = g.getMemberList();
	    	tmpList.clear();
	    	for(Member e : g.getMembers()) {
	    		tmpList.add(e.getMembername());
	    	}
	    }
	    /* XXX: Assigning config.isLogical based on 
	     * SocketInfo data is a big hack. I could not make it work 
	     * with normal yaml.load, hence had to go with this hack. 
	     */
	    mySocketInfo = config.getConfigSockInfo(localName);
	    if(mySocketInfo == null) {
	    	/*** ERROR ***/
	    	System.out.println("The local name is not correct.");
	    	System.exit(0);
	    }
	    
	    if (mySocketInfo.getClockType().equals("logical")) {
	    	config.isLogical = true;
	    } else {
	    	config.isLogical = false;
	    }

	}

	public void closeAllSockets() throws IOException {
		// Auto-generated method stub
		hostListenSocket.close();

		/* Close all other sockets in the sockets map */
		for (Map.Entry<SocketInfo, Socket> entry : sockets.entrySet()) {
			entry.getValue().close();
		}
		for (Map.Entry<String, ObjectOutputStream> entry : outputStreamMap
				.entrySet()) {
			entry.getValue().close();
		}
	}

	public ClockService getClockSer() {
		return clockSer;
	}

	public void setClockSer(ClockService clockSer) {
		this.clockSer = clockSer;
	}

	public String getLocalName() {
		return localName;
	}

	public void setLocalName(String localName) {
		this.localName = localName;
	}

	public void cleanUp() {
		this.delayRecvQueue.clear();
		this.delayRecvQueue.clear();
		this.recvQueue.clear();
		this.currSeqNum = 0;
		this.clockSer.cleanUp();

		if (!this.config.isLogical) {
			HashMap<String, Integer> map = this.clockSer.getTs()
					.getVectorClock();
			for (SocketInfo e : this.config.configuration) {
				map.put(e.getName(), 0);
			}
		}
	}

	public void checkAdd(Message msg) {

		if (config.getGroup(msg.getDest()) != null) { // multicast message

			SrcGroup srcGrp = new SrcGroup(msg.getSrc(), msg.getDest());
			int getNum = ((TimeStampedMessage) msg).getGrpSeqNum();
			NackItem ni = new NackItem(srcGrp, getNum);
			allMsg.put(ni, msg);

			int seenNum = seqNums.get(srcGrp);

			if (seenNum <= getNum) { // duplicate message. Ignore
				return;
			} else if ((seenNum + 1) == getNum) { // in order. add to recvQueue
				updateSequenceNumber(srcGrp);
				addToRecvQueue(recvQueue, msg);
				updateHoldback(msg);
			} else {
				addToHoldBack(msg);
				sendNACK();
			}
		} else { // regular message
			addToRecvQueue(recvQueue, msg);
		}
	}

	public void addToHoldBack(Message msg) {
		SrcGroup srcGrp = new SrcGroup(msg.getSrc(), msg.getDest());
		List<Message> messagesInGroup;
		if (holdBackMap.containsKey(srcGrp)) {
			messagesInGroup = holdBackMap.get(srcGrp);
			// insert in order
			int i;
			for (i = 0; i < messagesInGroup.size(); i++) {
				if (((TimeStampedMessage) msg).getGrpSeqNum() < 
						((TimeStampedMessage) messagesInGroup.get(i))
						.getGrpSeqNum()) {
					messagesInGroup.add(i, msg);
					break;
				}
			}

			if (i == messagesInGroup.size()) {
				messagesInGroup.add(msg);
			}
		} else {
			messagesInGroup = new ArrayList<Message>();
			messagesInGroup.add(msg);
		}

		holdBackMap.put(srcGrp, messagesInGroup);

		return;
	}

	public void updateSequenceNumber(SrcGroup srcGrp) {
		int curr;
		if(seqNums.containsKey(srcGrp)){
			curr = seqNums.get(srcGrp);
		} else{
			curr = 0;
		}
		seqNums.put(srcGrp, curr + 1);
	}

	public void updateHoldback(Message msg) {
		SrcGroup srcGrp = new SrcGroup(msg.getSrc(), msg.getDest());
		if (holdBackMap.containsKey(srcGrp)) {
			List<Message> messagesInGroup = holdBackMap.get(srcGrp);
			while (!messagesInGroup.isEmpty()
					&& (int) ((TimeStampedMessage) messagesInGroup.get(0))
							.getGrpSeqNum() == (int) (seqNums.get(srcGrp) + 1)) {
				updateSequenceNumber(srcGrp);
				addToRecvQueue(recvQueue, messagesInGroup.get(0));
				messagesInGroup.remove(0);
			}
		}
	}

	public void sendNACK() {

		for (Group g : config.getGroupList()) {
			if (g.getMemberList().contains(localName)) {
				List<NackItem> nackContent = new ArrayList<NackItem>();
				for(SocketInfo e : config.configuration) {
					List<NackItem> nackContentSrc = new ArrayList<NackItem>();
					SrcGroup srcGrp = new SrcGroup(e.getName(), g.getGroupName());
				
					if (holdBackMap.get(srcGrp) != null && !holdBackMap.get(srcGrp).isEmpty()) { 
						// something in holdback queue
						List<Message> hbQueue = holdBackMap.get(srcGrp);
						int seqNum = seqNums.get(srcGrp) + 1;
						Iterator<Message> queueIt = hbQueue.iterator();
						// Go through queue and populate missing msg NACKs
						while (queueIt.hasNext()) {
							Message curr = queueIt.next();
							while (seqNum < ((TimeStampedMessage) curr)
								.getGrpSeqNum()) {
								NackItem nack = new NackItem(srcGrp, seqNum);
								nackContent.add(nack);
								nackContentSrc.add(nack);
								seqNum++;
							}
							seqNum++;
						}
					} else { // nothing in holdback queue, just NACK next
/*debug sentence */
for(SrcGroup s : this.seqNums.keySet())
	System.out.println(s.getSrc() + " " + s.getGroupName() + " " + this.seqNums.get(s));
System.out.println("=========\n" + srcGrp.getSrc() + " " + srcGrp.getGroupName());
if(seqNums.containsKey(srcGrp)) {
	System.out.println("we find it");
}
						NackItem nack = new NackItem(srcGrp,
							seqNums.get(srcGrp) + 1);
						nackContent.add(nack);
						nackContentSrc.add(nack);
					}
					// send to original sender (might not be in group)
					TimeStampedMessage nackMsgSrc = new TimeStampedMessage(
						srcGrp.getSrc(), "NACK", nackContentSrc, null);
					doSend(nackMsgSrc, srcGrp.getSrc());
				}
				TimeStampedMessage nackMsg = new TimeStampedMessage(
						g.getGroupName(), "NACK", nackContent, null);
				checkSend(nackMsg); // skips rules and TS setting
			}
		}
	}

	public void getNACK(Message msg) {
		@SuppressWarnings("unchecked")
		List<NackItem> nackContent = (List<NackItem>) msg.getData();
		for (NackItem nack : nackContent) {
			if (allMsg.containsKey(nack)) { // if has message
				Message nackReply = new TimeStampedMessage(msg.getSrc(),
						"NACK REPLY", allMsg.get(nack), null);
				doSend(nackReply, nackReply.getDest());
			}
		}
	}

	public void addToRecvQueue(LinkedList<Message> recvQueue, Message msg) {
		int i = 0, size = 0;
		synchronized (recvQueue) {
			size = recvQueue.size();
			for (; i < size; i++) {
				TimeStampedMessage tmp = (TimeStampedMessage) recvQueue.get(i);
				if (tmp.getMsgTS().compare(
						((TimeStampedMessage) msg).getMsgTS()) != TimeStampRelation.greaterEqual) {
					break;
				}
			}
			recvQueue.add(i, msg);
		}
	}

	@Override
	public String toString() {
		return "MessagePasser [configFilename=" + configFilename
				+ ", localName=" + localName + ", hostListenSocket="
				+ hostListenSocket + ", hostSocketInfo=" + hostSocketInfo
				+ ", config=" + config + "]";
	}

	public boolean getIsLogical() {
		return this.config.isLogical;
	}

}