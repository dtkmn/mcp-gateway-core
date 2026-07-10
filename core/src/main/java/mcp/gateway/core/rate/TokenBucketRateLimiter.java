package mcp.gateway.core.rate;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Thread-safe token-bucket rate limiter keyed by caller, tool, workspace, or another runtime key.
 * Token-policy changes for an existing key take effect from the change onward;
 * elapsed time is never retroactively credited at a newly configured refill rate.
 */
public final class TokenBucketRateLimiter {
    /** Default key used when callers provide a blank or null key. */
    public static final String DEFAULT_KEY = "anonymous";

    private final ConcurrentHashMap<String, BucketState> buckets = new ConcurrentHashMap<>();
    private final Object bucketCreationLock = new Object();
    private final LongSupplier nanoClock;
    private final LongSupplier millisClock;

    /**
     * Creates a limiter using the system clocks.
     */
    public TokenBucketRateLimiter() {
        this(System::nanoTime, System::currentTimeMillis);
    }

    /**
     * Creates a limiter with caller-provided clocks for deterministic tests.
     *
     * @param nanoClock monotonic nanosecond clock
     * @param millisClock wall-clock millisecond clock
     */
    public TokenBucketRateLimiter(LongSupplier nanoClock, LongSupplier millisClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.millisClock = Objects.requireNonNull(millisClock, "millisClock must not be null");
    }

    /**
     * Attempts to consume one token from the bucket for the supplied key.
     *
     * @param key bucket key
     * @param policy rate-limit policy
     * @return {@code true} when a token was consumed or rate limiting is disabled
     */
    public boolean tryConsume(String key, Policy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        if (!policy.enabled()) {
            return true;
        }

        String normalizedKey = normalizeKey(key);
        while (true) {
            BucketState state = buckets.get(normalizedKey);
            if (state == null) {
                state = createBucketIfCapacityAllows(normalizedKey, policy);
                if (state == null) {
                    return false;
                }
            }

            synchronized (state) {
                if (buckets.get(normalizedKey) != state) {
                    continue;
                }
                applyPolicyAndRefill(state, policy);
                state.lastAccessMillis = millisClock.getAsLong();
                if (state.tokens >= 1.0d) {
                    state.tokens -= 1.0d;
                    return true;
                }
                return false;
            }
        }
    }

    /**
     * Estimates when a rejected caller should retry.
     *
     * @param key bucket key
     * @param policy rate-limit policy
     * @return retry delay in whole seconds
     */
    public long retryAfterSeconds(String key, Policy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        if (!policy.enabled()) {
            return policy.disabledRetryAfterSeconds();
        }

        BucketState state = buckets.get(normalizeKey(key));
        if (state == null) {
            return 1L;
        }

        synchronized (state) {
            applyPolicyAndRefill(state, policy);
            if (state.tokens >= 1.0d) {
                return 1L;
            }
            double missingTokens = 1.0d - state.tokens;
            double secondsUntilToken = missingTokens
                    * policy.refillPeriodSeconds()
                    / policy.refillTokens();
            return Math.max(1L, (long) Math.ceil(secondsUntilToken));
        }
    }

    int trackedKeyCount() {
        return buckets.size();
    }

    private void applyPolicyAndRefill(BucketState state, Policy policy) {
        long nowNanos = nanoClock.getAsLong();
        if (!state.usesSameTokenPolicy(policy)) {
            refill(
                    state,
                    state.capacity,
                    state.refillTokens,
                    state.refillPeriodSeconds,
                    nowNanos
            );
            state.tokens = Math.min(policy.capacity(), state.tokens);
            state.capacity = policy.capacity();
            state.refillTokens = policy.refillTokens();
            state.refillPeriodSeconds = policy.refillPeriodSeconds();
            return;
        }

        refill(state, policy.capacity(), policy.refillTokens(), policy.refillPeriodSeconds(), nowNanos);
    }

    private void refill(BucketState state,
                        int capacity,
                        int refillTokens,
                        long refillPeriodSeconds,
                        long nowNanos) {
        state.tokens = Math.min(capacity, state.tokens);
        long elapsedNanos = nowNanos - state.lastRefillNanos;
        if (elapsedNanos < 0L) {
            state.lastRefillNanos = nowNanos;
            return;
        }
        if (elapsedNanos == 0L) {
            return;
        }

        double elapsedSeconds = elapsedNanos / 1_000_000_000.0d;
        double tokensToAdd = elapsedSeconds * refillTokens / refillPeriodSeconds;

        state.tokens = Math.min(capacity, state.tokens + tokensToAdd);
        state.lastRefillNanos = nowNanos;
    }

    private void evictIfNeeded(Policy policy) {
        if (buckets.size() < policy.maxTrackedKeys()) {
            return;
        }

        long staleAgeMillis = saturatedMultiply(
                saturatedMultiply(policy.refillPeriodSeconds(), 1_000L),
                5L
        );
        long staleBefore = saturatedSubtract(millisClock.getAsLong(), staleAgeMillis);
        Iterator<Map.Entry<String, BucketState>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext() && buckets.size() >= policy.maxTrackedKeys()) {
            Map.Entry<String, BucketState> entry = iterator.next();
            BucketState state = entry.getValue();
            synchronized (state) {
                if (state.lastAccessMillis < staleBefore) {
                    buckets.remove(entry.getKey(), state);
                }
            }
        }
    }

    private BucketState createBucketIfCapacityAllows(String normalizedKey, Policy policy) {
        synchronized (bucketCreationLock) {
            BucketState existing = buckets.get(normalizedKey);
            if (existing != null) {
                return existing;
            }
            evictIfNeeded(policy);
            if (buckets.size() >= policy.maxTrackedKeys()) {
                return null;
            }

            BucketState newState = new BucketState(
                    policy,
                    nanoClock.getAsLong(),
                    millisClock.getAsLong()
            );
            buckets.put(normalizedKey, newState);
            return newState;
        }
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return DEFAULT_KEY;
        }
        return key.trim();
    }

    private static long saturatedMultiply(long value, long multiplier) {
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }

    private static long saturatedSubtract(long value, long nonNegativeAmount) {
        if (value < Long.MIN_VALUE + nonNegativeAmount) {
            return Long.MIN_VALUE;
        }
        return value - nonNegativeAmount;
    }

    /**
     * Immutable token-bucket configuration.
     *
     * @param enabled whether rate limiting is enabled
     * @param capacity maximum number of stored tokens
     * @param refillTokens tokens added during each refill period
     * @param refillPeriodSeconds refill period in seconds
     * @param maxTrackedKeys maximum tracked bucket keys, normalized to at least one
     * @param disabledRetryAfterSeconds retry delay returned when the policy is disabled
     */
    public record Policy(
            boolean enabled,
            int capacity,
            int refillTokens,
            long refillPeriodSeconds,
            int maxTrackedKeys,
            long disabledRetryAfterSeconds
    ) {
        /**
         * Creates a normalized token-bucket policy.
         *
         * @param enabled whether rate limiting is enabled
         * @param capacity maximum number of stored tokens
         * @param refillTokens tokens added during each refill period
         * @param refillPeriodSeconds refill period in seconds
         * @param maxTrackedKeys maximum tracked bucket keys
         * @param disabledRetryAfterSeconds retry delay returned when the policy is disabled
         */
        public Policy {
            capacity = Math.max(1, capacity);
            refillTokens = Math.max(1, refillTokens);
            refillPeriodSeconds = Math.max(1L, refillPeriodSeconds);
            maxTrackedKeys = Math.max(1, maxTrackedKeys);
            disabledRetryAfterSeconds = Math.max(1L, disabledRetryAfterSeconds);
        }
    }

    private static final class BucketState {
        private double tokens;
        private long lastRefillNanos;
        private volatile long lastAccessMillis;
        private int capacity;
        private int refillTokens;
        private long refillPeriodSeconds;

        private BucketState(Policy policy, long lastRefillNanos, long lastAccessMillis) {
            this.tokens = policy.capacity();
            this.lastRefillNanos = lastRefillNanos;
            this.lastAccessMillis = lastAccessMillis;
            this.capacity = policy.capacity();
            this.refillTokens = policy.refillTokens();
            this.refillPeriodSeconds = policy.refillPeriodSeconds();
        }

        private boolean usesSameTokenPolicy(Policy policy) {
            return capacity == policy.capacity()
                    && refillTokens == policy.refillTokens()
                    && refillPeriodSeconds == policy.refillPeriodSeconds();
        }
    }
}
