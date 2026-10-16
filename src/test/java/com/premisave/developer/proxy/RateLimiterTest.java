package com.premisave.developer.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private static final long MIN = 60_000;

    @Test
    void allowsUpToTheLimitThenRefusesWithRetryAfter() {
        RateLimiter l = new RateLimiter(3);
        long t = 10 * MIN + 5_000;
        assertEquals(2, l.tryAcquire("k", t).remaining());
        assertEquals(1, l.tryAcquire("k", t).remaining());
        assertEquals(0, l.tryAcquire("k", t).remaining());
        RateLimiter.Decision d = l.tryAcquire("k", t);
        assertFalse(d.allowed());
        assertEquals(55, d.retryAfterSeconds());
    }

    @Test
    void theWindowResetsEachMinute() {
        RateLimiter l = new RateLimiter(1);
        long t = 10 * MIN;
        assertTrue(l.tryAcquire("k", t).allowed());
        assertFalse(l.tryAcquire("k", t + 30_000).allowed());
        assertTrue(l.tryAcquire("k", t + MIN).allowed());
    }

    @Test
    void keysAreCountedSeparately() {
        RateLimiter l = new RateLimiter(1);
        assertTrue(l.tryAcquire("a", 5 * MIN).allowed());
        assertTrue(l.tryAcquire("b", 5 * MIN).allowed());
        assertFalse(l.tryAcquire("a", 5 * MIN).allowed());
    }
}
