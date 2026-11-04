package com.example.platform.upstream;

/**
 * @see #reason timeout, error or circuit_open: the DegradedSource reasons.
 */
public class UpstreamFailure extends RuntimeException {
    public final String source;
    public final String reason;
    /** Seconds until the circuit may let a probe through, or null if not applicable. */
    public final Double retryAfter;

    public UpstreamFailure(String source, String reason) {
        this(source, reason, null);
    }

    public UpstreamFailure(String source, String reason, Double retryAfter) {
        super(source + ": " + reason, null, false, false);
        this.source = source;
        this.reason = reason;
        this.retryAfter = retryAfter;
    }
}
