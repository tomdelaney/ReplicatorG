package replicatorg.machine;

import java.util.EnumMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Vector;

import javax.swing.JOptionPane;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import replicatorg.app.Base;
import replicatorg.app.exceptions.BuildFailureException;
import replicatorg.app.tools.XML;
import replicatorg.drivers.Driver;
import replicatorg.drivers.DriverFactory;
import replicatorg.drivers.OnboardParameters;
import replicatorg.drivers.RetryException;
import replicatorg.drivers.SDCardCapture;
import replicatorg.drivers.SimulationDriver;
import replicatorg.drivers.StopException;
import replicatorg.drivers.UsesSerial;
import replicatorg.machine.MachineController.JobTarget;
import replicatorg.machine.MachineController.MachineCommand;
import replicatorg.machine.builder.MachineBuilder;
import replicatorg.machine.builder.Direct;
import replicatorg.machine.builder.ToLocalFile;
import replicatorg.machine.builder.ToRemoteFile;
import replicatorg.machine.builder.UsingRemoteFile;
import replicatorg.machine.model.MachineModel;
import replicatorg.model.GCodeSource;
import replicatorg.model.GCodeSourceCollection;
import replicatorg.model.StringListSource;

/**
 * The MachineThread is responsible for communicating with the machine.
 * 
 * @author phooky
 * 
 */
class MachineThread extends Thread {

	// TODO: Rethink this.
	class Timer {
		private long lastEventTime = 0;
		private boolean enabled = false;
		private long intervalMs = 1000;
		
		public void start(long interval) {
			enabled = true;
			intervalMs = interval;
		}
		
		public void stop() {
			enabled = false;
		}
		
		// send out updates
		public boolean elapsed() {
			if (!enabled) {
				return false;
			}
			long curMillis = System.currentTimeMillis();
			if (lastEventTime + intervalMs <= curMillis) {
				lastEventTime = curMillis;
				
				return true;
			}
			return false;
		}
	}
	
	private Timer pollingTimer;

	// Link of machine commands to run
	Queue<MachineCommand> pendingQueue;
		
	// this is the xml config for this machine.
	private Node machineNode;
	
	private MachineController controller;
	
	// our warmup/cooldown commands
	private Vector<String> warmupCommands;
	private Vector<String> cooldownCommands;
	
	// The name of our machine.
	private String name;
	
	// Things that belong to a job
		// estimated build time in millis
		private double estimatedBuildTime = 0;
	
		// Build statistics
		private double startTimeMillis = -1;
	
	// Our driver object. Null when no driver is selected.
	private Driver driver = null;
	
	// the simulator driver
	private SimulationDriver simulator;
	
	private MachineState state = new MachineState();

	// ???
	MachineModel cachedModel = null;
	

	
	public MachineThread(MachineController controller, Node machineNode) {
		super("Machine Thread");
		
		pollingTimer = new Timer();
		
		pendingQueue = new LinkedList<MachineCommand>();
		
		// save our XML
		this.machineNode = machineNode;
		this.controller = controller;
		
		// load our various objects
		loadDriver();
		loadExtraPrefs();
		parseName();
	}


	private void loadExtraPrefs() {
		String[] commands = null;
		String command = null;

		warmupCommands = new Vector<String>();
		if (XML.hasChildNode(machineNode, "warmup")) {
			String warmup = XML.getChildNodeValue(machineNode, "warmup");
			commands = warmup.split("\n");

			for (int i = 0; i < commands.length; i++) {
				command = commands[i].trim();
				warmupCommands.add(new String(command));
				// System.out.println("Added warmup: " + command);
			}
		}

		cooldownCommands = new Vector<String>();
		if (XML.hasChildNode(machineNode, "cooldown")) {
			String cooldown = XML.getChildNodeValue(machineNode, "cooldown");
			commands = cooldown.split("\n");

			for (int i = 0; i < commands.length; i++) {
				command = commands[i].trim();
				cooldownCommands.add(new String(command));
				// System.out.println("Added cooldown: " + command);
			}
		}
	}
	
	private MachineBuilder machineBuilder;
	
	/**
	 * Build the provided gcodes.  This method does not return until the build is complete or has been terminated.
	 * The build target need not be an actual machine; it can be a file as well.  An "upload" is considered a build
	 * to a machine.  
	 * @param source The gcode to build.
	 * @return true if build terminated normally
	 * @throws BuildFailureException
	 * @throws InterruptedException
	 */
	private boolean buildCodesInternal(GCodeSource source) throws BuildFailureException, InterruptedException {
				
		// did we get any errors?
		if (!isSimulating()) {
			driver.checkErrors();
		}
		
		// This block happens at the end of the 
		// wait for driver to finish up.

		return true;
	}
	
	// Run a remote SD card build on the machine.
	private void buildRemoteInternal(String remoteName) {
		// Dump out if SD builds are unsupported on this machine
		if (!(driver instanceof SDCardCapture)) return;
		
		machineBuilder = new UsingRemoteFile((SDCardCapture)driver, remoteName);
		
		// TODO: what about this?
		driver.getCurrentPosition(); // reconcile position
	}
	
	private boolean startBuildToRemoteFile() {
//		if (!(driver instanceof SDCardCapture)) {
//			return false;
//		}
//		
//		SDCardCapture sdcc = (SDCardCapture)driver;
//		if (processSDResponse(sdcc.beginCapture(remoteName))) { 
//			buildInternal(currentSource);
//			Base.logger.info("Captured bytes: " +Integer.toString(sdcc.endCapture()));
//			return true;
//		}

		return false;
	}
	
	private boolean startBuildToFile() {
//		if (!(driver instanceof SDCardCapture)) {
//			return false;
//		}
//		
//		SDCardCapture sdcc = (SDCardCapture)driver;
//		try {
//			sdcc.beginFileCapture(remoteName); 
//			buildInternal(currentSource);
//			sdcc.endFileCapture();
//			return true;
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
		
		return false;
	}

	// Respond to a command from the machine controller
	void runCommand(MachineCommand command) {
		Base.logger.fine("Got command: " + command.type.toString());
		
		switch(command.type) {
		case CONNECT:
			if (state.getState() == MachineState.State.NOT_ATTACHED) {
				// TODO: Break this out so we wait for connection in the main loop.
				setState(new MachineState(MachineState.State.CONNECTING));
				
				driver.initialize();
				if (driver.isInitialized()) {
					readName();
					setState(MachineState.State.READY);
				} else {
					setState(MachineState.State.NOT_ATTACHED);
				}
			}
			break;
		case DISCONNECT:
			// TODO: This seems wrong
			if (state.isConnected()) {
				driver.uninitialize();
				setState(new MachineState(MachineState.State.NOT_ATTACHED));
			
				if (driver instanceof UsesSerial) {
					UsesSerial us = (UsesSerial)driver;
					us.setSerial(null);
				}
			}
			
			break;
		case RESET:
			if (state.isConnected()) {
				driver.reset();
				readName();
				setState(MachineState.State.READY);
			}
			break;
		case BUILD_DIRECT:
			if (state.isReady()) {
				startTimeMillis = System.currentTimeMillis();
				
				// TODO: When does this stop?
				pollingTimer.start(1000);

				if (!isSimulating()) {
					driver.getCurrentPosition(); // reconcile position
				}
				
				// Eventually, we want to be able to build just the job,
				// but for now send warmup + job + cooldown.
				Vector<GCodeSource> sources = new Vector<GCodeSource>();
				sources.add(new StringListSource(warmupCommands));
				sources.add(command.source);
				sources.add(new StringListSource(cooldownCommands));
				GCodeSource combinedSource = new GCodeSourceCollection(sources);
				
				machineBuilder = new Direct(driver, simulator, combinedSource);
				
				// TODO: This shouldn't be done here?
				driver.invalidatePosition();
				setState(new MachineState(MachineState.State.BUILDING));
			}
			break;
//		case SIMULATE:
//			// TODO: Implement this.
//			setState(new MachineState(MachineState.State.BUILDING));
//			break;
		case BUILD_TO_REMOTE_FILE:
			if (state.isReady()) {
				if (!(driver instanceof SDCardCapture)) {
					break;
				}
				
				startTimeMillis = System.currentTimeMillis();
				
				// TODO: When does this stop?
				pollingTimer.start(1000);

				if (!isSimulating()) {
					driver.getCurrentPosition(); // reconcile position
				}
				
				// Eventually, we want to be able to build just the job,
				// but for now send warmup + job + cooldown.
				Vector<GCodeSource> sources = new Vector<GCodeSource>();
				sources.add(new StringListSource(warmupCommands));
				sources.add(command.source);
				sources.add(new StringListSource(cooldownCommands));
				GCodeSource combinedSource = new GCodeSourceCollection(sources);
				
				machineBuilder = new ToRemoteFile(driver, simulator,
											combinedSource, command.remoteName);

				// TODO: This shouldn't be done here?
				driver.invalidatePosition();
				setState(new MachineState(MachineState.State.BUILDING));
			}
			break;
		case BUILD_TO_FILE:
			if (state.isReady()) {
				if (!(driver instanceof SDCardCapture)) {
					break;
				}
				
				startTimeMillis = System.currentTimeMillis();
				
				// TODO: When does this stop?
				pollingTimer.start(1000);

				if (!isSimulating()) {
					driver.getCurrentPosition(); // reconcile position
				}
				
				// Eventually, we want to be able to build just the job,
				// but for now send warmup + job + cooldown.
				Vector<GCodeSource> sources = new Vector<GCodeSource>();
				sources.add(new StringListSource(warmupCommands));
				sources.add(command.source);
				sources.add(new StringListSource(cooldownCommands));
				GCodeSource combinedSource = new GCodeSourceCollection(sources);
				
				machineBuilder = new ToLocalFile(driver, simulator,
											combinedSource, command.remoteName);

				// TODO: This shouldn't be done here?
				driver.invalidatePosition();
				setState(new MachineState(MachineState.State.BUILDING));
			}
			break;
		case BUILD_REMOTE:
			if (state.isReady()) {
				if (!(driver instanceof SDCardCapture)) break;
			
				machineBuilder = new UsingRemoteFile((SDCardCapture)driver, command.remoteName);
			
				// TODO: what about this?
				driver.getCurrentPosition(); // reconcile position
			
				setState(MachineState.State.BUILDING);
			}
			break;
//		case PAUSE:
//			if (state.isBuilding() && !state.isPaused()) {
//				MachineState newState = getMachineState();
//				newState.setPaused(true);
//				setState(newState);
//			}
//			break;
//		case UNPAUSE:
//			if (state.isBuilding() && state.isPaused()) {
//				MachineState newState = getMachineState();
//				newState.setPaused(false);
//				setState(newState);
//			}
//			break;
		case STOP_MOTION:
			if (state.isConnected()) {
				driver.stop(false);
				setState(MachineState.State.READY);
			}
			break;
		case STOP_ALL:
			// TODO: This should be handled at the driver level?
			driver.getMachine().currentTool().setTargetTemperature(0);
			driver.getMachine().currentTool().setPlatformTargetTemperature(0);
			
			driver.stop(true);
			setState(MachineState.State.READY);
			
			break;			
//		case DISCONNECT_REMOTE_BUILD:
//			// TODO: This is wrong.
//			
//			if (state.getState() == MachineState.State.BUILDING_REMOTE) {
//				return; // send no further packets to machine; let it go on its own
//			}
//			
//			if (state.isBuilding()) {
//				setState(MachineState.State.STOPPING);
//			}
//			break;
		case RUN_COMMAND:
			if (state.isConnected()) {
				boolean completed = false;
				// TODO: Don't get stuck in a loop here!
				
				while(!completed) {
					try {
						command.command.run(driver);
						completed = true;
					} catch (RetryException e) {
					} catch (StopException e) {
					}
				}
			}
			break;
		default:
			Base.logger.severe("Ignored command: " + command.type.toString());
		}
	}
	
	/**
	 * Main machine thread loop.
	 */
	public void run() {
		// This is our main loop.
		while (true) {
			// If we are building
			if ( state.getState() == MachineState.State.BUILDING ) {
				//run another instruction on the machine.
				machineBuilder.runNext();
				
				if (machineBuilder.finished()) {
					// TODO: Exit correctly.
					setState(MachineState.State.READY);
				}
				
				// Check the status poll machine.
				if (pollingTimer.elapsed()) {
					if (Base.preferences.getBoolean("build.monitor_temp",false)) {
						driver.readTemperature();
						controller.emitToolStatus(driver.getMachine().currentTool());
					}
				}
				
				// And send out a progress event
				// TODO: Should this be done here??
				MachineProgressEvent progress = 
					new MachineProgressEvent((double)System.currentTimeMillis()-startTimeMillis,
							estimatedBuildTime,
							machineBuilder.getLinesProcessed(),
							machineBuilder.getLinesTotal());
				controller.emitProgress(progress);
			}

			// Check for and run any control requests that might be in the queue.
			while (!pendingQueue.isEmpty()) {
				synchronized(pendingQueue) { runCommand(pendingQueue.remove()); }
			}
			
			// If there is nothing to do, sleep.
			if ( state.getState() != MachineState.State.BUILDING ) {
				try {
					synchronized(this) { wait(); }
				} catch(InterruptedException e) {
					break;
				}
			}
			
			// If we get interrupted, break out of the main loop.
			if (Thread.interrupted()) {
		        break;
			}
		}
		
		Base.logger.warning("MachineThread interrupted, terminating.");
	}
	
	public boolean scheduleRequest(MachineCommand request) {
		synchronized(pendingQueue) { pendingQueue.add(request); }
		synchronized(this) { notify(); }
		
		return true;
	}
	
	public boolean isReady() { return state.isReady(); }
	
	// TODO: Put this somewhere else
	/** True if the machine's build is going to the simulator. */
	public boolean isSimulating() {
		// TODO: implement this.
		return false;
	}
	
	// TODO: Put this somewhere else
	public boolean isInteractiveTarget() {
		if (machineBuilder != null) {
			return machineBuilder.isInteractive();
		}
		return false;
	}
	
	public JobTarget getTarget() {
		if (machineBuilder != null) {
			return machineBuilder.getTarget();
		}
		return JobTarget.NONE;
	}
	
	public int getLinesProcessed() {
		if (machineBuilder != null) {
			return machineBuilder.getLinesProcessed();
		}
		return -1;
	}
	
	public MachineState getMachineState() {
		return state.clone();
	}
	
	
	/**
	 * Set the a machine state.  If the state is not the current state, a state change
	 * event will be emitted and the machine thread will be notified.  
	 * @param state the new state of the machine.
	 */
	private void setState(MachineState state) {
		MachineState oldState = this.state;
		this.state = state;
		if (!oldState.equals(state)) {
			controller.emitStateChange(oldState,state);
		}
	}

	/**
	 * A helper for setting the machine state to a simple state.
	 * @param state The new state.
	 */
	private void setState(MachineState.State state) {
		MachineState newState = getMachineState();
		newState.setState(state);
		setState(newState);
	}
	
	
	public Driver getDriver() {
		return driver;
	}
	
	public SimulationDriver getSimulator() {
		return simulator;
	}
	
	synchronized public boolean isInitialized() {
		return (driver != null && driver.isInitialized());
	}
	
	public void loadDriver() {
		// load our utility drivers
		if (Base.preferences.getBoolean("machinecontroller.simulator",true)) {
			Base.logger.info("Loading simulator.");
			simulator = new SimulationDriver();
			simulator.setMachine(loadModel());
		}
		Node driverXml = null; 
		// load our actual driver
		NodeList kids = machineNode.getChildNodes();
		for (int j = 0; j < kids.getLength(); j++) {
			Node kid = kids.item(j);
			if (kid.getNodeName().equals("driver")) {
				driverXml = kid;
			}
		}
		driver = DriverFactory.factory(driverXml);
		driver.setMachine(getModel());
		// Initialization is now handled by the machine thread when it
		// is placed in a connecting state.
	}
	
	public void dispose() {
		if (driver != null) {
			driver.dispose();
		}
		if (simulator != null) {
			simulator.dispose();
		}
		setState(new MachineState(MachineState.State.NOT_ATTACHED));
	}
	
	public void readName() {
		if (driver instanceof OnboardParameters) {
			String n = ((OnboardParameters)driver).getMachineName();
			if (n != null && n.length() > 0) {
				name = n;
			}
			else {
				parseName(); // Use name from XML file instead of reusing name from last connected machine
			}
		}
	}
	
	private void parseName() {
		NodeList kids = machineNode.getChildNodes();

		for (int j = 0; j < kids.getLength(); j++) {
			Node kid = kids.item(j);

			if (kid.getNodeName().equals("name")) {
				name = kid.getFirstChild().getNodeValue().trim();
				return;
			}
		}

		name = "Unknown";
	}
	
	private MachineModel loadModel() {
		MachineModel model = new MachineModel();
		model.loadXML(machineNode);
		return model;
	}
	
	public MachineModel getModel() {
		if (cachedModel == null) { cachedModel = loadModel(); }
		return cachedModel;
	}
	
	// TODO: Make this a command.
	public void setEstimatedBuildTime(double estimatedBuildTime) {
		this.estimatedBuildTime = estimatedBuildTime;
	}
	
	public String getMachineName() { return name; }
}