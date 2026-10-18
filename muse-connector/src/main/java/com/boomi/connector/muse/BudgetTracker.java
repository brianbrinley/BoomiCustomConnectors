package com.boomi.connector.muse;

/**
 * Per-execution token accounting. Thread-safe: the bulk operation records from
 * several worker threads at once.
 *
 * Budgets are guardrails, not a ledger. Under bulk concurrency a few requests
 * can be in flight when the limit trips, so an execution can overshoot by a
 * small margin. What the tracker guarantees is that no <em>new</em> request
 * starts once a budget is reached.
 */
public final class BudgetTracker {

    private final MuseConnection _conn;
    private long _inputTokens;
    private long _outputTokens;

    public BudgetTracker(MuseConnection conn) {
        _conn = conn;
    }

    public synchronized void record(long promptTokens, long completionTokens) {
        _inputTokens += promptTokens;
        _outputTokens += completionTokens;
    }

    /**
     * Why the next request must be skipped, or null when the execution is
     * still within budget.
     */
    public synchronized String blockReason() {
        if (_conn.getBudgetMaxInputTokens() > 0 && _inputTokens >= _conn.getBudgetMaxInputTokens()) {
            return "input token budget reached (" + _inputTokens + " used)";
        }
        if (_conn.getBudgetMaxOutputTokens() > 0 && _outputTokens >= _conn.getBudgetMaxOutputTokens()) {
            return "output token budget reached (" + _outputTokens + " used)";
        }
        return null;
    }

    public synchronized String summary() {
        return _inputTokens + " input tokens, " + _outputTokens + " output tokens";
    }
}
