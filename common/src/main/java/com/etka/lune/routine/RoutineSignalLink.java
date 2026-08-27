package com.etka.lune.routine;

/**
 * An execution-pulse wire leaving a Signal Relay.  The target port is only used when the target
 * is another relay; ordinary command cards have one execution input and use {@code -1}.
 */
public final class RoutineSignalLink {

    /** The numbered output on the relay. */
    public int outputPort;

    /** The node that receives the pulse. */
    public String targetNodeId;

    /** The numbered input on a relay target, or -1 for an ordinary command input. */
    public int targetPort = -1;

    public RoutineSignalLink() {}

    public RoutineSignalLink(int outputPort, String targetNodeId, int targetPort) {
        this.outputPort = outputPort;
        this.targetNodeId = targetNodeId;
        this.targetPort = targetPort;
    }
}
