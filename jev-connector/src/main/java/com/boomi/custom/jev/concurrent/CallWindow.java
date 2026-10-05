package com.boomi.custom.jev.concurrent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs small groups ("windows") of calls, up to a fixed number at a time, and returns their outcomes in the order
 * the calls were given.
 *
 * <ul>
 *   <li>With a concurrency of 1, or a window of one call, everything runs on the calling thread and no thread is
 *       ever created.</li>
 *   <li>If the runtime refuses to create worker threads, the window falls back to the calling thread for the rest
 *       of its life and says so once through {@link #takeFallbackNotice()}.</li>
 *   <li>A call that throws does not affect the others; its exception is returned as that call's outcome.</li>
 * </ul>
 *
 * Not thread-safe: one instance belongs to one operation execution and is driven from one thread.
 */
public final class CallWindow implements AutoCloseable {

    /** Upper bound on concurrent calls, whatever the operation field says. */
    public static final int MAX_CONCURRENCY = 16;

    private static final Logger LOG = Logger.getLogger(CallWindow.class.getName());
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    /** Result of one call: either a value or the exception it threw. */
    public static final class Outcome<T> {
        private final T value;
        private final Exception error;

        private Outcome(T value, Exception error) {
            this.value = value;
            this.error = error;
        }

        public boolean failed() {
            return error != null;
        }

        public T getValue() {
            return value;
        }

        public Exception getError() {
            return error;
        }
    }

    private final int concurrency;
    private final ThreadFactory threadFactory;
    private ExecutorService pool;
    private boolean fellBack;
    private String fallbackNotice;

    public CallWindow(int concurrency) {
        this(concurrency, CallWindow::newWorkerThread);
    }

    /** The thread factory is pluggable so tests can simulate a runtime that refuses to create threads. */
    public CallWindow(int concurrency, ThreadFactory threadFactory) {
        this.concurrency = clamp((long) concurrency);
        this.threadFactory = threadFactory;
    }

    /** Maps the operation field to a usable value: blank or below 1 means 1, above the cap means the cap. */
    public static int clamp(Long requested) {
        if (requested == null || requested < 1L) {
            return 1;
        }
        return requested > MAX_CONCURRENCY ? MAX_CONCURRENCY : requested.intValue();
    }

    public int getConcurrency() {
        return concurrency;
    }

    /** True once worker threads could not be created and calls run on the calling thread instead. */
    public boolean hasFallenBack() {
        return fellBack;
    }

    /** Returns the fallback explanation the first time it is asked for after a fallback, otherwise null. */
    public String takeFallbackNotice() {
        String notice = fallbackNotice;
        fallbackNotice = null;
        return notice;
    }

    /**
     * Runs the calls and returns one outcome per call, in the same order. Callers pass at most
     * {@link #getConcurrency()} calls per window; a larger window still works, with the extra calls queued.
     */
    public <T> List<Outcome<T>> run(List<? extends Callable<T>> calls) {
        List<Outcome<T>> outcomes = new ArrayList<>(calls.size());
        if (concurrency <= 1 || fellBack || calls.size() <= 1) {
            for (Callable<T> call : calls) {
                outcomes.add(invoke(call));
            }
            return outcomes;
        }

        List<Future<T>> futures = new ArrayList<>(calls.size());
        try {
            ExecutorService executor = pool();
            for (Callable<T> call : calls) {
                futures.add(executor.submit(call));
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            // A restricted runtime refuses the thread (SecurityException), or the host has no threads left to
            // give ("unable to create native thread"). Either way the task that failed was not accepted.
            fallBack(e);
        }

        boolean interrupted = false;
        for (Future<T> future : futures) {
            if (interrupted) {
                future.cancel(true);
                outcomes.add(new Outcome<>(null, new IOException("Interrupted while waiting for JEV")));
                continue;
            }
            try {
                outcomes.add(new Outcome<>(future.get(), null));
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                outcomes.add(new Outcome<>(null, cause instanceof Exception ? (Exception) cause : e));
            } catch (InterruptedException e) {
                interrupted = true;
                Thread.currentThread().interrupt();
                future.cancel(true);
                outcomes.add(new Outcome<>(null, new IOException("Interrupted while waiting for JEV", e)));
            }
        }

        // Calls that were never handed to a worker (thread creation failed part-way) run here instead
        for (int i = futures.size(); i < calls.size(); i++) {
            outcomes.add(interrupted
                    ? new Outcome<>(null, new IOException("Interrupted while waiting for JEV"))
                    : invoke(calls.get(i)));
        }
        if (fellBack) {
            shutdownPool();
        }
        return outcomes;
    }

    @Override
    public void close() {
        shutdownPool();
    }

    private static <T> Outcome<T> invoke(Callable<T> call) {
        try {
            return new Outcome<>(call.call(), null);
        } catch (Exception e) {
            return new Outcome<>(null, e);
        }
    }

    private ExecutorService pool() {
        if (pool == null) {
            pool = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), threadFactory);
        }
        return pool;
    }

    private void fallBack(Throwable cause) {
        fellBack = true;
        fallbackNotice = "Max Concurrent Requests is " + concurrency + " but this runtime did not allow worker "
                + "threads (" + cause + "). JEV requests run one at a time instead.";
        LOG.log(Level.WARNING, fallbackNotice);
    }

    private void shutdownPool() {
        if (pool != null) {
            try {
                pool.shutdownNow();
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Could not shut down JEV worker threads", e);
            }
            pool = null;
        }
    }

    private static Thread newWorkerThread(Runnable task) {
        Thread thread = new Thread(task, "jev-connector-" + THREAD_COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }
}
