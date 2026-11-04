package com.example.platform.upstream;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Allows retries only up to a fraction of recent traffic. During an outage, per-call retries alone
 * would multiply the load on an upstream that is already struggling.
 */
public class RetryBudget {
    private final double ratio;
    private final double maxTokens;
    private final ReentrantLock lock = new ReentrantLock();
    private double tokens;

    public RetryBudget() {
        this(0.2, 10.0);
    }

    public RetryBudget(double ratio, double maxTokens) {
        this.ratio = ratio;
        this.maxTokens = maxTokens;
        this.tokens = maxTokens;
    }

    public void recordRequest() {
        lock.lock();
        try {
            tokens = Math.min(maxTokens, tokens + ratio);
        } finally {
            lock.unlock();
        }
    }

    public boolean trySpend() {
        lock.lock();
        try {
            if (tokens < 1) {
                return false;
            }
            tokens -= 1;
            return true;
        } finally {
            lock.unlock();
        }
    }
}
