package com.boomi.connector.muse;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Per-execution spend accounting. Thread-safe: the bulk operation records from
 * several worker threads at once.
 *
 * Budgets are guardrails, not a ledger. Under bulk concurrency a few requests
 * can be in flight when the limit trips, so an execution can overshoot by a
 * small margin. What the tracker guarantees is that no <em>new</em> request
 * starts once a budget is reached.
 */
public final class BudgetTracker {

    private static final BigDecimal MILLION = new BigDecimal(1_000_000);

    private final MuseConnection _conn;
    private long _inputTokens;
    private long _outputTokens;
    private BigDecimal _costUsd = BigDecimal.ZERO;

    public BudgetTracker(MuseConnection conn) {
        _conn = conn;
    }

    public synchronized void record(long promptTokens, long completionTokens) {
        _inputTokens += promptTokens;
        _outputTokens += completionTokens;
        _costUsd = _costUsd.add(costOf(promptTokens, completionTokens));
    }

    private BigDecimal costOf(long promptTokens, long completionTokens) {
        BigDecimal in = new BigDecimal(promptTokens)
                .multiply(_conn.getPricePerMillionInputUsd())
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        BigDecimal out = new BigDecimal(completionTokens)
                .multiply(_conn.getPricePerMillionOutputUsd())
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        return in.add(out);
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
        if (_conn.getBudgetMaxCostUsd() != null
                && _costUsd.compareTo(_conn.getBudgetMaxCostUsd()) >= 0) {
            return "cost budget reached ($" + _costUsd.setScale(4, RoundingMode.HALF_UP) + " used)";
        }
        return null;
    }

    public synchronized String summary() {
        return _inputTokens + " input tokens, " + _outputTokens + " output tokens, ~$"
                + _costUsd.setScale(4, RoundingMode.HALF_UP) + " estimated";
    }
}
