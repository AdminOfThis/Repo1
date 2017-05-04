package control;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;

import data.Address;
import data.Button;
import data.LightBulb;
import data.LightState;

public class Sender {

	private static final Logger		LOG					= Logger.getLogger(Sender.class);
	private static final String[]	ARGS				= new String[] { "sudo", "/bin/bash", "-c", "./openmilight \"B0 0D 33 C2 CA 0F A0\"" };
	private static final File		BULB_LIST_FILE		= new File("./bulbs.data");
	private static final int		SEQUENCE_RANGE		= 255;
	private static final int		SEQUENCE_SPACE		= 10;
	private static Sender			instance;
	private boolean					isLinux;
	private Thread					thread;
	private Queue<LightState>		queue				= new LinkedBlockingQueue<>();
	private int						currentBrightness	= 19;
	private int						currentColor		= 0;
	private Button					currentButton		= Button.NONE;
	private int						currentMode			= 0x00;
	private int						currentGroup		= 0;
	private Process					proc;
	private OutputStreamWriter		writer;
	private ArrayList<LightBulb>	bulbList			= new ArrayList<>();

	public static Sender getInstance() {
		if (instance == null) {
			instance = new Sender();
		}
		return instance;
	}

	@SuppressWarnings("unchecked")
	private Sender() {
		String os = System.getProperty("os.name").toLowerCase();
		LOG.info("Detected OS: " + os);
		if (!os.contains("linux")) {
			LOG.warn("!!!=== Will only simulate Sender ===!!!");
			isLinux = false;
		} else {
			isLinux = true;
		}
		bulbList = (ArrayList<LightBulb>) FileUtil.loadList(BULB_LIST_FILE);
		checkAndStartSendThread();
	}

	private void checkAndStartSendThread() {
		if (thread == null) {
			thread = new Thread(new Runnable() {

				@Override
				public void run() {
					while (true) {
						if (queue.peek() == null) {
							Thread.yield();
							continue;
						}
						try {
							if ((proc == null || !proc.isAlive()) && isLinux) {
								LOG.info("Starting Sender");
								LOG.debug("Building new process");
								ProcessBuilder pb = new ProcessBuilder(ARGS);
								try {
									proc = pb.start();
									OutputStream outStream = proc.getOutputStream();
									writer = new OutputStreamWriter(outStream);
								}
								catch (IOException e) {
									LOG.error(e);
								}
							}
							String cmd = buildCommand(queue.poll());
							LOG.debug(cmd);
							if (isLinux()) {
								for (int run = 0; run < SEQUENCE_RANGE / SEQUENCE_SPACE; run++) {
									writer.write(cmd + " " + Integer.toHexString(SEQUENCE_SPACE * run) + "\n");
									writer.flush();
									Thread.sleep(50);
								}
							}
							LOG.trace("Sended, " + queue.size() + " queued");
						}
						catch (Exception e) {
							LOG.error("Sender crashed");
							LOG.debug(e);
							if (proc != null) {
								proc.destroyForcibly();
							}
						}
					}
				}
			});
			thread.start();
		}
	}

	private String buildCommand(final LightState poll) {
		Button button = poll.getButton();
		if (button != null) {
			this.currentButton = button;
		}
		switch (button) {
		case ALL_ON:
			currentGroup = 0;
			break;
		case GROUP1_ON:
			currentGroup = 1;
			break;
		case GROUP2_ON:
			currentGroup = 2;
			break;
		case GROUP3_ON:
			currentGroup = 3;
			break;
		case GROUP4_ON:
			currentGroup = 4;
			break;
		default:
			break;
		}
		String buttonString = Integer.toHexString(currentButton.getCmd());
		if (buttonString.length() < 2) {
			buttonString = "0" + buttonString;
		}
		buttonString = buttonString.toUpperCase();
		int color = poll.getColor();
		if (color >= 0) {
			this.currentColor = (color + 0x1B) % 255;
			this.currentMode = 0;
		}
		String colorString = Integer.toHexString(currentColor);
		if (colorString.length() < 2) {
			colorString = "0" + colorString;
		}
		colorString = colorString.toUpperCase();
		int brightness = poll.getBrightness();
		if (brightness >= 0) {
			this.currentBrightness = brightness;
		}
		String brightString = Integer.toHexString(parseToBrightness(currentBrightness) + currentGroup);
		if (brightString.length() < 2) {
			brightString = "0" + brightString;
		}
		brightString = brightString.toUpperCase();
		int mode = poll.getMode();
		if (mode >= 0) {
			this.currentMode = mode;
		}
		byte[] bytes = ByteBuffer.allocate(4).putInt(poll.getAddress().getAddress()).array();
		String addressString = String.format("%02X", bytes[2]) + " " + String.format("%02X", bytes[3]);

		return "B" + Integer.toHexString(currentMode) + " " + addressString + " " + colorString + " " + brightString + " " + buttonString;
	}

	public void update(LightState state) {
		queue.add(state);
		checkAndStartSendThread();
	}

	public void queueFirst(ArrayList<LightState> states) {
		Queue<LightState> oldQueue = new LinkedBlockingQueue<>(queue);
		queue.clear();
		queue.addAll(states);
		queue.addAll(oldQueue);
	}

	public void queueFirst(LightState state) {
		Queue<LightState> oldQueue = new LinkedBlockingQueue<>(queue);
		queue.clear();
		queue.add(state);
		queue.addAll(oldQueue);
	}

	private int parseToBrightness(int round) {
		int newRound = -(round - 16) * 8;
		if (newRound < 0) {
			newRound = newRound + 256;
		}
		return Math.abs(newRound);
	}

	public boolean isLinux() {
		return isLinux;
	}

	public Address connectLightBulb(Address idToAddress) {

		LOG.info("SEND CONNECT");
		// Send signal for 3 seconds
		Button btn = null;
		switch (idToAddress.getGroup()) {
		case 1:
			btn = Button.GROUP1_ON;
			break;
		case 2:
			btn = Button.GROUP2_ON;
			break;
		case 3:
			btn = Button.GROUP3_ON;
			break;
		case 4:
			btn = Button.GROUP4_ON;
			break;
		}
		LightState state = new LightState(btn);
		state.setAddress(idToAddress);
		queueFirst(state);
		return idToAddress;
	}

	public ArrayList<LightBulb> getBulbList() {
		return new ArrayList<>(bulbList);
	}

	public void addToBulbList(LightBulb bulb) {
		if (!bulbList.contains(bulb)) {
			bulbList.add(bulb);
			FileUtil.saveList(bulbList, BULB_LIST_FILE);
		}
	}

	public void removeFromBulbList(LightBulb bulb) {
		if (bulbList.contains(bulb)) {
			bulbList.remove(bulb);
			FileUtil.saveList(bulbList, BULB_LIST_FILE);
		}
	}

}
