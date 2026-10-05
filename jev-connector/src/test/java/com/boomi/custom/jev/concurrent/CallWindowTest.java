package com.boomi.custom.jev.concurrent;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CallWindowTest {

    /** Counts the threads it hands out; optionally refuses after a number of them. */
    private static final class CountingFactory implements ThreadFactory {
        final AtomicInteger created = new AtomicInteger();
        final int allowed;

        CountingFactory(int allowed) {
            this.allowed = allowed;
        }

        @Override
        public Thread newThread(Runnable task) {
            if (created.get() >= allowed) {
                throw new SecurityException("thread creation is not permitted");
            }
            created.incrementAndGet();
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        }
    }

    private static List<Callable<String>> calls(int count, List<String> ranOn) {
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int n = i;
            calls.add(() -> {
                ranOn.add(Thread.currentThread().getName());
                return "result-" + n;
            });
        }
        return calls;
    }

    private static List<String> values(List<CallWindow.Outcome<String>> outcomes) {
        List<String> values = new ArrayList<>();
        for (CallWindow.Outcome<String> outcome : outcomes) {
            assertFalse(outcome.failed());
            values.add(outcome.getValue());
        }
        return values;
    }

    @Test
    public void clampsTheOperationField() {
        assertEquals(1, CallWindow.clamp(null));
        assertEquals(1, CallWindow.clamp(0L));
        assertEquals(1, CallWindow.clamp(-3L));
        assertEquals(1, CallWindow.clamp(1L));
        assertEquals(8, CallWindow.clamp(8L));
        assertEquals(CallWindow.MAX_CONCURRENCY, CallWindow.clamp(16L));
        assertEquals(CallWindow.MAX_CONCURRENCY, CallWindow.clamp(500L));
        assertEquals(CallWindow.MAX_CONCURRENCY, CallWindow.clamp(Long.MAX_VALUE));
        assertEquals(CallWindow.MAX_CONCURRENCY, new CallWindow(99).getConcurrency());
    }

    @Test
    public void concurrencyOfOneNeverCreatesAThread() {
        CountingFactory factory = new CountingFactory(Integer.MAX_VALUE);
        List<String> ranOn = Collections.synchronizedList(new ArrayList<>());
        try (CallWindow window = new CallWindow(1, factory)) {
            assertEquals(List.of("result-0", "result-1", "result-2"), values(window.run(calls(3, ranOn))));
        }
        assertEquals(0, factory.created.get());
        assertEquals(Collections.nCopies(3, Thread.currentThread().getName()), ranOn);
    }

    @Test
    public void singleCallRunsInLineEvenWhenParallelIsAllowed() {
        CountingFactory factory = new CountingFactory(Integer.MAX_VALUE);
        List<String> ranOn = Collections.synchronizedList(new ArrayList<>());
        try (CallWindow window = new CallWindow(8, factory)) {
            assertEquals(List.of("result-0"), values(window.run(calls(1, ranOn))));
            assertTrue(window.run(new ArrayList<Callable<String>>()).isEmpty());
        }
        assertEquals(0, factory.created.get());
    }

    @Test
    public void outcomesComeBackInCallOrder() {
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int n = i;
            calls.add(() -> {
                Thread.sleep((4 - n) * 40L); // the first call finishes last
                return n;
            });
        }
        try (CallWindow window = new CallWindow(4)) {
            List<CallWindow.Outcome<Integer>> outcomes = window.run(calls);
            for (int i = 0; i < 4; i++) {
                assertEquals(Integer.valueOf(i), outcomes.get(i).getValue());
            }
        }
    }

    @Test
    public void neverRunsMoreThanTheConfiguredNumberAtOnce() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            int n = i;
            calls.add(() -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(40L);
                } finally {
                    running.decrementAndGet();
                }
                return n;
            });
        }
        CountingFactory factory = new CountingFactory(Integer.MAX_VALUE);
        try (CallWindow window = new CallWindow(3, factory)) {
            assertEquals(9, window.run(calls).size());
            assertEquals(9, window.run(calls).size());
        }
        assertEquals(3, peak.get());
        assertEquals("threads are reused across windows", 3, factory.created.get());
    }

    @Test
    public void oneFailingCallDoesNotAffectTheOthers() {
        List<Callable<String>> calls = new ArrayList<>();
        calls.add(() -> "first");
        calls.add(() -> {
            throw new IOException("connection reset");
        });
        calls.add(() -> {
            throw new IllegalStateException("bug");
        });
        calls.add(() -> "last");
        try (CallWindow window = new CallWindow(4)) {
            List<CallWindow.Outcome<String>> outcomes = window.run(calls);
            assertEquals("first", outcomes.get(0).getValue());
            assertTrue(outcomes.get(1).getError() instanceof IOException);
            assertEquals("connection reset", outcomes.get(1).getError().getMessage());
            assertTrue(outcomes.get(2).getError() instanceof IllegalStateException);
            assertEquals("last", outcomes.get(3).getValue());
        }
    }

    @Test
    public void fallsBackToTheCallingThreadWhenThreadsAreRefused() {
        CountingFactory factory = new CountingFactory(0);
        List<String> ranOn = Collections.synchronizedList(new ArrayList<>());
        try (CallWindow window = new CallWindow(4, factory)) {
            assertEquals(List.of("result-0", "result-1", "result-2", "result-3"), values(window.run(calls(4, ranOn))));
            assertTrue(window.hasFallenBack());
            String notice = window.takeFallbackNotice();
            assertNotNull(notice);
            assertTrue(notice, notice.contains("one at a time"));
            assertNull("reported once", window.takeFallbackNotice());

            // later windows keep working and do not try again
            assertEquals(List.of("result-0", "result-1"), values(window.run(calls(2, ranOn))));
            assertNull(window.takeFallbackNotice());
        }
        assertEquals(Collections.nCopies(6, Thread.currentThread().getName()), ranOn);
    }

    @Test
    public void fallingBackPartWayRunsEveryCallExactlyOnce() {
        CountingFactory factory = new CountingFactory(1);
        AtomicInteger[] runs = new AtomicInteger[4];
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int n = i;
            runs[n] = new AtomicInteger();
            calls.add(() -> {
                runs[n].incrementAndGet();
                return n;
            });
        }
        try (CallWindow window = new CallWindow(4, factory)) {
            List<CallWindow.Outcome<Integer>> outcomes = window.run(calls);
            assertTrue(window.hasFallenBack());
            for (int i = 0; i < 4; i++) {
                assertEquals(Integer.valueOf(i), outcomes.get(i).getValue());
                assertEquals(1, runs[i].get());
            }
        }
    }

    @Test
    public void interruptedCallerGetsAnErrorForEveryPendingCall() {
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> {
                Thread.sleep(5_000L);
                return "never";
            });
        }
        Thread caller = Thread.currentThread();
        try (CallWindow window = new CallWindow(3)) {
            caller.interrupt();
            List<CallWindow.Outcome<String>> outcomes = window.run(calls);
            assertTrue(Thread.interrupted()); // also clears the flag for the rest of the test run
            assertEquals(3, outcomes.size());
            for (CallWindow.Outcome<String> outcome : outcomes) {
                assertTrue(outcome.getError() instanceof IOException);
            }
        }
        assertSame(caller, Thread.currentThread());
    }
}
