package com.flashsale.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Fixed-window rate-limit counter backed by Redis.
 *
 * Key format: {@code rl:{scope}:{windowIndex}}, where {@code scope} is
 * "{clientIp}:{productId}" and {@code windowIndex} is
 * {@code epochSeconds / windowSeconds}. The trailing index buckets requests
 * into discrete time slots — when the second ticks past a window boundary,
 * a brand-new key is used and the counter restarts from 0.
 *
 * --- Why a Lua script, not INCR-then-EXPIRE in Java code ---
 *
 *  Single Lua script: `INCR; if n == 1 then EXPIRE end`.
 *  This is the canonical fixed-window pattern.
 *
 *  The naive alternative — calling {@code redis.opsForValue().increment(key)}
 *  then {@code redis.expire(key, ttl)} as two separate Java statements — has
 *  a fatal race:
 *
 *    Process A: INCR → counter = 1
 *    Process A: <crashes / loses Redis connection / GC pause before EXPIRE>
 *    Process B: INCR → counter = 2 → ... → counter = N
 *
 *  The key now has NO TTL. It lives forever in Redis. Every future request
 *  for that (scope, window) tuple sees a counter that never resets. The
 *  buyer is permanently rate-limited and the entry leaks memory.
 *
 *  Wrapping both ops in a single Lua call makes the pair atomic from
 *  Redis's perspective — it either both runs or neither does. No window
 *  in which a counter exists without a TTL.
 *
 * --- Fail-open semantics ---
 *
 *  Any {@link DataAccessException} from Redis (down, network blip, OOM) →
 *  log at ERROR and return {@link #ALLOWED}. Stock correctness is still
 *  guaranteed by the DB atomic UPDATE downstream; we prefer availability
 *  over strictness when the limiter itself is broken. This is a deliberate
 *  policy decision — switching to fail-closed would refuse all purchases
 *  during a Redis outage, which is a worse outcome.
 */
@Service
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    public static final String KEY_PREFIX = "rl:";
    public static final int ALLOWED = 0;

    private static final String CHECK_LUA = """
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return n
            """;

    /** Null when no Redis bean is present — fail-open mode for the whole JVM. */
    private final StringRedisTemplate redis;
    private final RateLimitProperties props;
    private final DefaultRedisScript<Long> checkScript;

    public RateLimitService(ObjectProvider<StringRedisTemplate> redisProvider,
                            RateLimitProperties props) {
        this.redis = redisProvider.getIfAvailable();
        this.props = props;
        this.checkScript = new DefaultRedisScript<>(CHECK_LUA, Long.class);
        if (this.redis == null) {
            log.info("RateLimitService: no StringRedisTemplate bean — running fail-open (limits disabled)");
        }
    }

    /**
     * Increment the counter for {@code scope} in the current window. If the
     * resulting count is over {@link RateLimitProperties#getMaxAttempts()},
     * returns the seconds-until-next-window for the client to wait (suitable
     * for a {@code Retry-After} header). Otherwise returns {@link #ALLOWED}.
     *
     * Fail-open: returns {@link #ALLOWED} on Redis unavailable / errored.
     */
    public int checkAndCount(String scope) {
        if (redis == null) return ALLOWED;

        long windowSeconds = props.getWindowSeconds();
        long now = Instant.now().getEpochSecond();
        long windowIndex = now / windowSeconds;
        String key = KEY_PREFIX + scope + ":" + windowIndex;

        Long count;
        try {
            count = redis.execute(checkScript, List.of(key), String.valueOf(windowSeconds));
        } catch (DataAccessException e) {
            log.error("Rate-limit check failed (Redis unreachable); failing open for scope={}: {}",
                    scope, e.toString());
            return ALLOWED;
        }

        if (count == null || count <= props.getMaxAttempts()) {
            return ALLOWED;
        }

        // Over limit — compute seconds remaining to the next window boundary.
        long secondsIntoWindow = now % windowSeconds;
        return (int) (windowSeconds - secondsIntoWindow);
    }
}
