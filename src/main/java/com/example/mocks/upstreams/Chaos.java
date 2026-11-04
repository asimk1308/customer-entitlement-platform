package com.example.mocks.upstreams;

/** A fault to inject into one upstream. */
public class Chaos {
    public String upstream;
    public int latencyMs;
    public Integer errorStatus;
    /** How many requests to fail. Null with errorStatus set means every request. */
    public Integer failNext;
    /** Apply this many writes, then fail the response: the case where the outcome looks unknown. */
    public int loseResponseNext;
}
