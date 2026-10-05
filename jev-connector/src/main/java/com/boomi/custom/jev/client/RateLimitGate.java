package com.boomi.custom.jev.client;

/**
 * A pause shared by every worker of one operation execution. When JEV rate-limits one call (429), the client that
 * saw it closes the gate for the back-off period, and the other workers wait at the gate before their next request
 * instead of each discovering the limit on its own.
 *
 * <p>Thread-safe. Only used when the operation runs more than one request at a time.
 */
public final class RateLimitGate {

    /** Pluggable so tests don't depend on wall-clock time. */
    public interface Clock {
        long millis();
    }

    private final Clock clock;
    private long resumeAt;

    public RateLimitGate() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    public RateLimitGate(Clock clock) {
        this.clock = clock;
        this.resumeAt = clock.millis();
    }

    /** Closes the gate for at least {@code millis} from now. A shorter pause never shortens a longer one. */
    public synchronized void pauseFor(long millis) {
        if (millis > 0) {
            resumeAt = Math.max(resumeAt, clock.millis() + millis);
        }
    }

    /** Milliseconds until the gate opens; zero or less when it is open. */
    public synchronized long remainingMillis() {
        return resumeAt - clock.millis();
    }

    /**
     * Waits out the current pause, if any. Sleeps at most once: if the gate is closed again in the meantime, the
     * caller's own request finds out and backs off through the normal retry path.
     */
    public void awaitOpen(JevClient.Sleeper sleeper) throws InterruptedException {
        long wait = remainingMillis();
        if (wait > 0) {
            sleeper.sleep(wait);
        }
    }
}
