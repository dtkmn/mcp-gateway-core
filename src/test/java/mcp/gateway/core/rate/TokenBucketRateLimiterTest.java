package mcp.gateway.core.rate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        assertEquals(42L, limiter.retryAfterSeconds("client-a", policy));
        assertEquals(0, limiter.trackedKeyCount());
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
    void staleBucketsCanBeEvictedToAdmitNewKeysWithoutGrowingPastCapacity() {
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

        nowMillis.addAndGet(5_001L);
        assertTrue(limiter.tryConsume("client-new", policy));
        assertTrue(limiter.trackedKeyCount() <= 100);
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
            assertTrue(allowed <= 100);
            assertTrue(denied >= attemptCount - 100);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
