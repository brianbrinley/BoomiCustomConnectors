package com.boomi.custom.jev.client;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RateLimitGateTest {

    private final AtomicLong now = new AtomicLong(5_000L);
    private final List<Long> sleeps = new ArrayList<>();
    private final RateLimitGate gate = new RateLimitGate(now::get);

    @Test
    public void startsOpen() throws Exception {
        gate.awaitOpen(sleeps::add);
        assertTrue(sleeps.isEmpty());
        assertTrue(gate.remainingMillis() <= 0);
    }

    @Test
    public void waitsForTheRemainingPause() throws Exception {
        gate.pauseFor(2_000L);
        now.addAndGet(500L);
        gate.awaitOpen(sleeps::add);
        assertEquals(List.of(1_500L), sleeps);
    }

    @Test
    public void shorterPauseDoesNotShortenALongerOne() {
        gate.pauseFor(3_000L);
        gate.pauseFor(100L);
        assertEquals(3_000L, gate.remainingMillis());
    }

    @Test
    public void longerPauseExtendsTheGate() {
        gate.pauseFor(1_000L);
        gate.pauseFor(4_000L);
        assertEquals(4_000L, gate.remainingMillis());
    }

    @Test
    public void opensAgainOnceThePauseHasPassed() throws Exception {
        gate.pauseFor(1_000L);
        now.addAndGet(1_000L);
        gate.awaitOpen(sleeps::add);
        assertTrue(sleeps.isEmpty());
    }

    @Test
    public void zeroOrNegativePauseIsIgnored() {
        gate.pauseFor(0L);
        gate.pauseFor(-50L);
        assertTrue(gate.remainingMillis() <= 0);
    }
}
