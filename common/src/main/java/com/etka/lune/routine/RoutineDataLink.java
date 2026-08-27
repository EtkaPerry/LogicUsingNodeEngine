package com.etka.lune.routine;

/** A data wire from one node output port to a parameter on another node. */
public final class RoutineDataLink {

    /** The source node id. */
    public String sourceNodeId;

    /** The source data port id, for example {@code "count"}. */
    public String sourcePort;

    public RoutineDataLink() {}

    public RoutineDataLink(String sourceNodeId, String sourcePort) {
        this.sourceNodeId = sourceNodeId;
        this.sourcePort = sourcePort;
    }
}
