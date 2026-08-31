package com.etka.lune.task;

/** A data wire from one node output port to a parameter on another node. */
public final class TaskDataLink {

    /** The source node id. */
    public String sourceNodeId;

    /** The source data port id, for example {@code "count"}. */
    public String sourcePort;

    public TaskDataLink() {}

    public TaskDataLink(String sourceNodeId, String sourcePort) {
        this.sourceNodeId = sourceNodeId;
        this.sourcePort = sourcePort;
    }
}
