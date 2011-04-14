package replicatorg.machine;

import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;

import replicatorg.app.Base;


/** Thread that handles callbacks for events from the machine controller.
 * TODO: There /has/ to be a better way of doing this.
 * @author mattmets
 *
 */
public class MachineCallbackHandler extends Thread {
	
	// Send messages to these listeners
	private Vector<MachineListener> listeners = new Vector<MachineListener>();

	// We have to send these kinds of messages.
	// TODO: Make this abstract.
	ConcurrentLinkedQueue<MachineStateChangeEvent> machineStateChangeEventQueue;
	ConcurrentLinkedQueue<MachineProgressEvent> machineProgressEventQueue;
	ConcurrentLinkedQueue<MachineToolStatusEvent> machineToolStatusEventQueue;
	
	public MachineCallbackHandler() {
		super("Machine Callback Handler");
		
		machineStateChangeEventQueue = new ConcurrentLinkedQueue<MachineStateChangeEvent>();
		machineProgressEventQueue = new ConcurrentLinkedQueue<MachineProgressEvent>();
		machineToolStatusEventQueue = new ConcurrentLinkedQueue<MachineToolStatusEvent>();
	}
	
	@Override
	public void run() {
		// If we have any messages to send out, do so, otherwise wait.
		try {
			while(true) {
				while (!machineStateChangeEventQueue.isEmpty()) {
					for (MachineListener l : listeners) {
						l.machineStateChanged(machineStateChangeEventQueue.peek());
					}
					machineStateChangeEventQueue.remove();
				}
				
				while (!machineProgressEventQueue.isEmpty()) {
					for (MachineListener l : listeners) {
						l.machineProgress(machineProgressEventQueue.peek());
					}
					machineProgressEventQueue.remove();
				}
				
				while (!machineToolStatusEventQueue.isEmpty()) {
					for (MachineListener l : listeners) {
						l.toolStatusChanged(machineToolStatusEventQueue.peek());
					}
					machineToolStatusEventQueue.remove();
				}
					
				sleep(100);
//				wait();
			}
		} catch (InterruptedException e) {
			// Terminate!
		}
	}
	
	public void addMachineStateListener(MachineListener listener) {
		// TODO: Is this thread safe?
		listeners.add(listener);
		// TODO: Was this important?
//		listener.machineStateChanged(new MachineStateChangeEvent(this,
//				getMachineState()));
	}

	public void removeMachineStateListener(MachineListener listener) {
		// TODO: Is this thread safe?
		listeners.remove(listener);
	}

	synchronized public void schedule(MachineStateChangeEvent status) {
		machineStateChangeEventQueue.add(status);
	}

	synchronized public void schedule(MachineProgressEvent progress) {
		machineProgressEventQueue.add(progress);
	}

	synchronized public void schedule(MachineToolStatusEvent e) {
		machineToolStatusEventQueue.add(e);
	}
}