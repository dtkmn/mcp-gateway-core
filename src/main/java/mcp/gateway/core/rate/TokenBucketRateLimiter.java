package mcp.gateway.core.rate;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class TokenBucketRateLimiter {
    public static final String DEFAULT_KEY = "anonymous";

    private final ConcurrentHashMap<String, BucketState> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;
    private final LongSupplier millisClock;

    public TokenBucketRateLimiter() {
        this(System::nanoTime, System::currentTimeMillis);
    }

    public TokenBucketRateLimiter(LongSupplier nanoClock, LongSupplier millisClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.millisClock = Objects.requireNonNull(millisClock, "millisClock must not be null");
    }

    public boolean tryConsume(String key, Policy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        if (!policy.enabled()) {
            return true;
        }

        String normalizedKey = normalizeKey(key);
        BucketState state = buckets.get(normalizedKey);
        if (state == null) {
            state = createBucketIfCapacityAllows(normalizedKey, policy);
            if (state == null) {
                return false;
            }
        }

        synchronized (state) {
            refill(state, policy);
            state.lastAccessMillis = millisClock.getAsLong();
            if (state.tokens >= 1.0d) {
                state.tokens -= 1.0d;
                return true;
            }
            return false;
        }
    }

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
            refill(state, policy);
            if (state.tokens >= 1.0d) {
                return 1L;
            }
            long refillPeriodNanos = policy.refillPeriodSeconds() * 1_000_000_000L;
            double nanosPerToken = refillPeriodNanos / (double) policy.refillTokens();
            long nowNanos = nanoClock.getAsLong();
            double missingTokens = 1.0d - state.tokens;
            long nanosUntilToken = Math.max(
                    1L,
                    (long) Math.ceil(missingTokens * nanosPerToken) - (nowNanos - state.lastRefillNanos)
            );
            long seconds = (long) Math.ceil(nanosUntilToken / 1_000_000_000.0d);
            return Math.max(1L, seconds);
        }
    }

    int trackedKeyCount() {
        return buckets.size();
    }

    private void refill(BucketState state, Policy policy) {
        state.tokens = Math.min(policy.capacity(), state.tokens);
        long refillPeriodNanos = policy.refillPeriodSeconds() * 1_000_000_000L;
        long nowNanos = nanoClock.getAsLong();
        long elapsedNanos = Math.max(0L, nowNanos - state.lastRefillNanos);
        if (elapsedNanos == 0L) {
            return;
        }

        double tokensToAdd = (elapsedNanos / (double) refillPeriodNanos) * policy.refillTokens();
        if (tokensToAdd <= 0.0d) {
            return;
        }

        state.tokens = Math.min(policy.capacity(), state.tokens + tokensToAdd);
        state.lastRefillNanos = nowNanos;
    }

    private void evictIfNeeded(Policy policy) {
        if (buckets.size() < policy.maxTrackedKeys()) {
            return;
        }

        long staleBefore = millisClock.getAsLong() - (policy.refillPeriodSeconds() * 1000L * 5L);
        Iterator<Map.Entry<String, BucketState>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext() && buckets.size() >= policy.maxTrackedKeys()) {
            Map.Entry<String, BucketState> entry = iterator.next();
            if (entry.getValue().lastAccessMillis < staleBefore) {
                buckets.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private BucketState createBucketIfCapacityAllows(String normalizedKey, Policy policy) {
        evictIfNeeded(policy);
        if (buckets.size() >= policy.maxTrackedKeys()) {
            return null;
        }

        BucketState newState = new BucketState(
                policy.capacity(),
                nanoClock.getAsLong(),
                millisClock.getAsLong()
        );
        BucketState existing = buckets.putIfAbsent(normalizedKey, newState);
        if (existing != null) {
            return existing;
        }
        if (buckets.size() <= policy.maxTrackedKeys()) {
            return newState;
        }
        buckets.remove(normalizedKey, newState);
        return buckets.get(normalizedKey);
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return DEFAULT_KEY;
        }
        return key.trim();
    }

    public record Policy(
            boolean enabled,
            int capacity,
            int refillTokens,
            long refillPeriodSeconds,
            int maxTrackedKeys,
            long disabledRetryAfterSeconds
    ) {
        public Policy {
            capacity = Math.max(1, capacity);
            refillTokens = Math.max(1, refillTokens);
            refillPeriodSeconds = Math.max(1L, refillPeriodSeconds);
            maxTrackedKeys = Math.max(100, maxTrackedKeys);
            disabledRetryAfterSeconds = Math.max(1L, disabledRetryAfterSeconds);
        }
    }

    private static final class BucketState {
        private double tokens;
        private long lastRefillNanos;
        private long lastAccessMillis;

        private BucketState(double tokens, long lastRefillNanos, long lastAccessMillis) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
            this.lastAccessMillis = lastAccessMillis;
        }
    }
}
