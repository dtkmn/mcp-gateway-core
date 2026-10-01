package mcp.gateway.core.rate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    private final AtomicLong nowNanos = new AtomicLong();
    private final AtomicLong nowMillis = new AtomicLong();
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(
            nowNanos::get,
            nowMillis::get
    );

    @Test
    void allowsUntilCapacityIsExhaustedThenCalculatesRetryAfter() {
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true,
                2,
                1,
                10,
                100,
                30
        );

        assertTrue(limiter.tryConsume(" client-a ", policy));
        assertTrue(limiter.tryConsume("client-a", policy));
        assertFalse(limiter.tryConsume("client-a", policy));
        assertEquals(10L, limiter.retryAfterSeconds("client-a", policy));
    }

    @Test
    void returnsConsumptionAndRetryDelayFromOneAttempt() {
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true,
                1,
                1,
                10,
                100,
                30
        );

        TokenBucketRateLimiter.Attempt allowed = limiter.attempt("client-a", policy);
        nowNanos.addAndGet(2_500_000_000L);
        nowMillis.addAndGet(2_500L);
        TokenBucketRateLimiter.Attempt rejected = limiter.attempt("client-a", policy);

        assertTrue(allowed.allowed());
        assertEquals(0L, allowed.retryAfterSeconds());
        assertFalse(rejected.allowed());
        assertEquals(8L, rejected.retryAfterSeconds());
    }

    @Test
    void refillsTokensOverTime() {
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true,
                1,
                1,
                10,
                100,
                30
        );

        assertTrue(limiter.tryConsume("client-a", policy));
        assertFalse(limiter.tryConsume("client-a", policy));

        nowNanos.addAndGet(10_000_000_000L);
        nowMillis.addAndGet(10_000L);

        assertTrue(limiter.tryConsume("client-a", policy));
    }

    @Test
    void disabledPolicyAllowsRequestsAndReturnsConfiguredRetryAfter() {
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                false,
                0,
                0,
                0,
                0,
                42
        );

        assertTrue(limiter.tryConsume("client-a", policy));
        assertEquals(new TokenBucketRateLimiter.Attempt(true, 0L), limiter.attempt("client-a", policy));
        assertEquals(42L, limiter.retryAfterSeconds("client-a", policy));
        assertEquals(0, limiter.trackedKeyCount());
    }

    @Test
    void attemptResultNormalizesRetryDelay() {
        assertEquals(0L, new TokenBucketRateLimiter.Attempt(true, 30L).retryAfterSeconds());
        assertEquals(1L, new TokenBucketRateLimiter.Attempt(false, 0L).retryAfterSeconds());
    }

    @Test
    void attemptRejectsNullPolicy() {
        assertThrows(NullPointerException.class, () -> limiter.attempt("client-a", null));
    }

    @Test
    void blankKeysShareAnonymousBucket() {
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true,
                1,
                1,
                60,
                100,
                30
        );

        assertTrue(limiter.tryConsume(" ", policy));
        assertFalse(limiter.tryConsume(null, policy));
    }

    @Test
    void freshKeySprayCannotGrowTrackedBucketsBeyondCapacity() {
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true,
                1,
                1,
                60,
                100,
                30
        );

        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.tryConsume("client-" + i, policy));
        }

        assertFalse(limiter.tryConsume("client-over-cap", policy));
        assertEquals(100, limiter.trackedKeyCount());
    }

    @Test
    void honorsSmallConfiguredTrackedKeyLimits() {
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true,
                1,
                1,
                60,
                2,
                30
        );

        assertTrue(limiter.tryConsume("client-a", policy));
        assertTrue(limiter.tryConsume("client-b", policy));
        assertEquals(new TokenBucketRateLimiter.Attempt(false, 1L), limiter.attempt("client-c", policy));
        assertEquals(2, limiter.trackedKeyCount());
    }

    @Test
    void fullyReplenishedIdleBucketsCanBeEvictedWithoutGrowingPastCapacity() {
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true,
                1,
                1,
                1,
                100,
                30
        );

        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.tryConsume("client-" + i, policy));
        }

        nowNanos.addAndGet(5_001_000_000L);
        nowMillis.addAndGet(5_001L);
        assertTrue(limiter.tryConsume("client-new", policy));
        assertEquals(100, limiter.trackedKeyCount());
    }

    @Test
    void boundsRetirementChecksForUniqueAndRepeatedRejectedKeysWithoutErasingDebt() {
        AtomicLong clockReads = new AtomicLong();
        TokenBucketRateLimiter countedLimiter = new TokenBucketRateLimiter(
                () -> {
                    clockReads.incrementAndGet();
                    return nowNanos.get();
                }, nowMillis::get
        );
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true, 10, 1, 1, 10_000, 30
        );
        for (int key = 0; key < policy.maxTrackedKeys(); key++) {
            for (int token = 0; token < policy.capacity(); token++) {
                assertTrue(countedLimiter.tryConsume("client-" + key, policy));
            }
        }
        nowNanos.set(6_000_000_000L);
        nowMillis.set(6_000L);
        clockReads.set(0L);

        for (int request = 0; request < 100; request++) {
            if (request % 2 == 0) {
                assertEquals(new TokenBucketRateLimiter.Attempt(false, 1L),
                        countedLimiter.attempt("new-" + request, policy));
            } else {
                assertFalse(countedLimiter.tryConsume(" repeated-new-key ", policy));
            }
        }

        assertEquals(6_400L, clockReads.get());
        assertEquals(10_000, countedLimiter.trackedKeyCount());
        clockReads.set(0L);
        for (int token = 0; token < 6; token++) {
            assertTrue(countedLimiter.tryConsume("client-0", policy));
        }
        assertEquals(new TokenBucketRateLimiter.Attempt(false, 1L),
                countedLimiter.attempt("client-0", policy));
        assertEquals(7L, clockReads.get());
    }

    @Test
    void rotatesPastFreshBucketsToReachRetirableCandidatesOnLaterAttempts() {
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true, 1, 1, 1, 129, 30
        );
        for (int key = 0; key < policy.maxTrackedKeys(); key++) {
            assertTrue(limiter.tryConsume("client-" + key, policy));
        }
        nowNanos.set(6_000_000_000L);
        nowMillis.set(6_000L);
        for (int key = 0; key < 128; key++) {
            assertTrue(limiter.tryConsume("client-" + key, policy));
        }

        assertEquals(new TokenBucketRateLimiter.Attempt(false, 1L), limiter.attempt("new", policy));
        assertFalse(limiter.tryConsume("new", policy));
        assertTrue(limiter.tryConsume("new", policy));
        assertEquals(129, limiter.trackedKeyCount());
        assertFalse(limiter.tryConsume("client-0", policy));
        assertFalse(limiter.tryConsume("new", policy));
    }

    @Test
    void retiresInBoundedBatchesWhenIncomingTrackedKeyLimitIsSmaller() {
        TokenBucketRateLimiter.Policy initialPolicy = new TokenBucketRateLimiter.Policy(
                true, 1, 1, 1, 129, 30
        );
        for (int key = 0; key < initialPolicy.maxTrackedKeys(); key++) {
            assertTrue(limiter.tryConsume("client-" + key, initialPolicy));
        }
        nowNanos.set(6_000_000_000L);
        nowMillis.set(6_000L);
        TokenBucketRateLimiter.Policy smallerPolicy = new TokenBucketRateLimiter.Policy(
                true, 1, 1, 1, 1, 30
        );

        assertEquals(new TokenBucketRateLimiter.Attempt(false, 1L), limiter.attempt("new", smallerPolicy));
        assertEquals(65, limiter.trackedKeyCount());
        assertFalse(limiter.tryConsume("new", smallerPolicy));
        assertEquals(1, limiter.trackedKeyCount());
        assertTrue(limiter.tryConsume("new", smallerPolicy));
        assertEquals(1, limiter.trackedKeyCount());
    }

    @Test
    void returningKeysRemainRetirableAcrossRepeatedReplacement() {
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true, 1, 1, 1, 2, 30
        );
        assertTrue(limiter.tryConsume("client-0", policy));
        assertTrue(limiter.tryConsume("client-1", policy));
        for (int cycle = 0; cycle < 100; cycle++) {
            nowNanos.addAndGet(6_000_000_000L);
            nowMillis.addAndGet(6_000L);
            String returningKey = "client-" + ((cycle + 2) % 3);
            assertTrue(limiter.tryConsume(returningKey, policy));
            assertEquals(new TokenBucketRateLimiter.Attempt(false, 1L), limiter.attempt(returningKey, policy));
            assertEquals(2, limiter.trackedKeyCount());
        }
    }

    @Test
    void clockFailureDoesNotLoseRetirementCandidate() {
        AtomicBoolean failClock = new AtomicBoolean();
        TokenBucketRateLimiter throwingLimiter = new TokenBucketRateLimiter(
                () -> {
                    if (failClock.get()) {
                        throw new IllegalStateException("clock unavailable");
                    }
                    return nowNanos.get();
                }, nowMillis::get
        );
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true, 1, 1, 1, 1, 30
        );
        assertTrue(throwingLimiter.tryConsume("original", policy));
        nowNanos.set(6_000_000_000L);
        nowMillis.set(6_000L);
        failClock.set(true);
        assertThrows(IllegalStateException.class, () -> throwingLimiter.attempt("new", policy));
        assertEquals(1, throwingLimiter.trackedKeyCount());

        failClock.set(false);
        assertTrue(throwingLimiter.tryConsume("new", policy));
        assertEquals(1, throwingLimiter.trackedKeyCount());
    }

    @Test
    void concurrentRejectedAdmissionsKeepRetirementWorkBounded() throws Exception {
        AtomicLong clockReads = new AtomicLong();
        TokenBucketRateLimiter countedLimiter = new TokenBucketRateLimiter(
                () -> {
                    clockReads.incrementAndGet();
                    return nowNanos.get();
                }, nowMillis::get
        );
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true, 10, 1, 1, 256, 30
        );
        for (int key = 0; key < policy.maxTrackedKeys(); key++) {
            for (int token = 0; token < policy.capacity(); token++) {
                assertTrue(countedLimiter.tryConsume("client-" + key, policy));
            }
        }
        nowNanos.set(6_000_000_000L);
        nowMillis.set(6_000L);
        clockReads.set(0L);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            List<Callable<TokenBucketRateLimiter.Attempt>> attempts = new ArrayList<>();
            for (int request = 0; request < 100; request++) {
                String key = "new-" + request;
                attempts.add(() -> countedLimiter.attempt(key, policy));
            }
            for (Future<TokenBucketRateLimiter.Attempt> future : executor.invokeAll(attempts, 5, TimeUnit.SECONDS)) {
                assertEquals(new TokenBucketRateLimiter.Attempt(false, 1L), future.get(5, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(6_400L, clockReads.get());
        assertEquals(256, countedLimiter.trackedKeyCount());
    }

    @Test
    void retainsPartiallyReplenishedBucketsAfterFiveRefillPeriods() {
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true, 10, 1, 10, 1, 30
        );
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryConsume("client-a", policy));
        }

        nowNanos.addAndGet(50_001_000_000L);
        nowMillis.addAndGet(50_001L);

        assertEquals(new TokenBucketRateLimiter.Attempt(false, 1L), limiter.attempt("client-new", policy));
        assertEquals(1, limiter.trackedKeyCount());
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryConsume("client-a", policy));
        }
        assertEquals(new TokenBucketRateLimiter.Attempt(false, 10L), limiter.attempt("client-a", policy));
    }

    @Test
    void wallClockAdvanceAloneDoesNotEraseBucketDebt() {
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true, 1, 1, 10, 1, 30
        );
        assertTrue(limiter.tryConsume("client-a", policy));

        nowMillis.addAndGet(50_001L);

        assertEquals(new TokenBucketRateLimiter.Attempt(false, 1L), limiter.attempt("client-new", policy));
        assertEquals(new TokenBucketRateLimiter.Attempt(false, 10L), limiter.attempt("client-a", policy));
        assertEquals(1, limiter.trackedKeyCount());
    }

    @Test
    void incomingFasterPolicyDoesNotShortenExistingBucketsIdlePeriod() {
        TokenBucketRateLimiter.Policy slowPolicy = new TokenBucketRateLimiter.Policy(
                true, 1, 1, 10, 1, 30
        );
        TokenBucketRateLimiter.Policy fastPolicy = new TokenBucketRateLimiter.Policy(
                true, 1, 1, 1, 1, 30
        );
        assertTrue(limiter.tryConsume("client-a", slowPolicy));

        nowNanos.addAndGet(10_001_000_000L);
        nowMillis.addAndGet(10_001L);

        assertEquals(new TokenBucketRateLimiter.Attempt(false, 1L), limiter.attempt("client-new", fastPolicy));
        assertTrue(limiter.tryConsume("client-a", slowPolicy));
        assertEquals(new TokenBucketRateLimiter.Attempt(false, 10L), limiter.attempt("client-a", slowPolicy));
        assertEquals(1, limiter.trackedKeyCount());
    }

    @Test
    void retirementUsesUpdatedBucketPolicyWithoutRetroactiveRefillCredit() {
        TokenBucketRateLimiter.Policy slowPolicy = new TokenBucketRateLimiter.Policy(
                true, 2, 1, 100, 1, 30
        );
        TokenBucketRateLimiter.Policy fastPolicy = new TokenBucketRateLimiter.Policy(
                true, 2, 1, 10, 1, 30
        );
        TokenBucketRateLimiter.Policy incomingPolicy = new TokenBucketRateLimiter.Policy(
                true, 1, 1, 1_000, 1, 30
        );
        assertTrue(limiter.tryConsume("client-a", slowPolicy));
        assertTrue(limiter.tryConsume("client-a", slowPolicy));

        nowNanos.addAndGet(50_000_000_000L);
        nowMillis.addAndGet(50_000L);
        assertEquals(new TokenBucketRateLimiter.Attempt(false, 5L), limiter.attempt("client-a", fastPolicy));

        nowNanos.addAndGet(51_000_000_000L);
        nowMillis.addAndGet(51_000L);

        assertEquals(new TokenBucketRateLimiter.Attempt(true, 0L), limiter.attempt("client-new", incomingPolicy));
        assertEquals(new TokenBucketRateLimiter.Attempt(false, 1_000L), limiter.attempt("client-new", incomingPolicy));
        assertEquals(1, limiter.trackedKeyCount());
    }

    @Test
    void concurrentAdmissionAndConsumptionPreserveAvailableTokensAndTrackedKeyBound() throws Exception {
        int maxTrackedKeys = 8;
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true, 1, 1, 1, maxTrackedKeys, 30
        );
        for (int i = 0; i < maxTrackedKeys; i++) {
            assertTrue(limiter.tryConsume("client-" + i, policy));
        }
        nowNanos.addAndGet(5_001_000_000L);
        nowMillis.addAndGet(5_001L);

        int attemptCount = 32;
        ExecutorService executor = Executors.newFixedThreadPool(attemptCount);
        CountDownLatch ready = new CountDownLatch(attemptCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TokenBucketRateLimiter.Attempt>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attemptCount; i++) {
                String key = "client-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return limiter.attempt(key, policy);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int allowed = 0;
            for (Future<TokenBucketRateLimiter.Attempt> future : futures) {
                TokenBucketRateLimiter.Attempt attempt = future.get(5, TimeUnit.SECONDS);
                assertEquals(attempt.allowed() ? 0L : 1L, attempt.retryAfterSeconds());
                if (attempt.allowed()) {
                    allowed++;
                }
            }

            assertEquals(maxTrackedKeys, allowed);
            assertEquals(maxTrackedKeys, limiter.trackedKeyCount());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentFreshKeySprayCannotGrowTrackedBucketsBeyondCapacity() throws Exception {
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true,
                1,
                1,
                60,
                100,
                30
        );
        int attemptCount = 400;
        int workerCount = 64;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> attempts = new ArrayList<>();

        for (int i = 0; i < attemptCount; i++) {
            String key = "spray-client-" + i;
            attempts.add(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return limiter.tryConsume(key, policy);
            });
        }

        List<Future<Boolean>> futures = attempts.stream()
                .map(executor::submit)
                .toList();
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int allowed = 0;
            int denied = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(5, TimeUnit.SECONDS)) {
                    allowed++;
                } else {
                    denied++;
                }
            }

            assertEquals(100, limiter.trackedKeyCount());
            assertEquals(100, allowed);
            assertTrue(denied >= attemptCount - 100);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentSameKeyAttemptsReturnConsistentResults() throws Exception {
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true,
                25,
                1,
                60,
                100,
                30
        );
        int attemptCount = 100;
        int workerCount = 32;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<TokenBucketRateLimiter.Attempt>> attempts = new ArrayList<>();

        for (int i = 0; i < attemptCount; i++) {
            attempts.add(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return limiter.attempt("shared-client", policy);
            });
        }

        List<Future<TokenBucketRateLimiter.Attempt>> futures = attempts.stream()
                .map(executor::submit)
                .toList();
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int allowed = 0;
            int rejected = 0;
            for (Future<TokenBucketRateLimiter.Attempt> future : futures) {
                TokenBucketRateLimiter.Attempt attempt = future.get(5, TimeUnit.SECONDS);
                if (attempt.allowed()) {
                    allowed++;
                    assertEquals(0L, attempt.retryAfterSeconds());
                } else {
                    rejected++;
                    assertEquals(60L, attempt.retryAfterSeconds());
                }
            }

            assertEquals(25, allowed);
            assertEquals(75, rejected);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void avoidsOverflowForVeryLongRefillPeriods() {
        long refillPeriodSeconds = Long.MAX_VALUE / 1_000_000_000L + 1L;
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true,
                1,
                1,
                refillPeriodSeconds,
                1,
                30
        );

        assertTrue(limiter.tryConsume("client-a", policy));
        assertFalse(limiter.tryConsume("client-a", policy));
        assertEquals(refillPeriodSeconds, limiter.retryAfterSeconds("client-a", policy));
    }

    @Test
    void recoversSafelyWhenInjectedMonotonicClockRegresses() {
        TokenBucketRateLimiter.Policy policy = new TokenBucketRateLimiter.Policy(
                true,
                1,
                1,
                10,
                1,
                30
        );
        nowNanos.set(100_000_000_000L);

        assertTrue(limiter.tryConsume("client-a", policy));

        nowNanos.set(90_000_000_000L);
        assertEquals(10L, limiter.retryAfterSeconds("client-a", policy));

        nowNanos.set(100_000_000_000L);
        assertTrue(limiter.tryConsume("client-a", policy));
    }

    @Test
    void doesNotApplyChangedRefillRateRetroactively() {
        TokenBucketRateLimiter.Policy slowPolicy = new TokenBucketRateLimiter.Policy(
                true,
                1,
                1,
                100,
                1,
                30
        );
        TokenBucketRateLimiter.Policy fastPolicy = new TokenBucketRateLimiter.Policy(
                true,
                1,
                1,
                10,
                1,
                30
        );

        assertTrue(limiter.tryConsume("client-a", slowPolicy));
        nowNanos.addAndGet(50_000_000_000L);
        assertFalse(limiter.tryConsume("client-a", fastPolicy));

        nowNanos.addAndGet(5_000_000_000L);
        assertTrue(limiter.tryConsume("client-a", fastPolicy));
    }
}
