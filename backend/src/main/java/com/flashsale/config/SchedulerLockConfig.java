package com.flashsale.config;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.Optional;

/**
 * Wires ShedLock so the {@code @Scheduled} jobs ({@code OrderExpiryJob.sweep},
 * {@code RedisInventoryService.reconcile}) execute on a single pod per tick
 * once the backend Deployment is scaled to multiple replicas.
 *
 * <p><b>This is the efficiency layer, not the correctness layer.</b> The jobs
 * are already safe to run concurrently — {@code sweep} guards stock release
 * behind an atomic conditional status transition, and {@code reconcile} is
 * idempotent (it sets Redis = DB truth). ShedLock just avoids the wasted
 * N-times-redundant scan when there are N replicas.
 *
 * <p>--- Graceful degradation (no Redis) ---
 *
 *  {@code @EnableSchedulerLock} requires a {@link LockProvider} bean to exist.
 *  When Redis autoconfiguration is absent (unit tests, DB-only deployments)
 *  there is no {@link RedisConnectionFactory}, so {@link ObjectProvider#getIfAvailable()}
 *  returns null and we fall back to {@link NoOpLockProvider} — every lock
 *  acquisition succeeds, so each job runs on every tick exactly like a plain
 *  {@code @Scheduled} method. That is the correct behaviour for single-node,
 *  and the jobs' own guards keep it safe.
 *
 *  We deliberately use {@code ObjectProvider} rather than
 *  {@code @ConditionalOnBean(RedisConnectionFactory.class)} for the same
 *  autoconfiguration-ordering reason documented on {@code RedisInventoryService}:
 *  a conditional on an autoconfig-created bean evaluates before that bean
 *  exists. {@code ObjectProvider} is a deferred lookup that sidesteps the
 *  timing issue.
 *
 *  {@code defaultLockAtMostFor = "55s"}: a hard ceiling below the 60s tick, so
 *  if a lock-holder pod crashes mid-run the lock auto-expires before the next
 *  scheduled fire and the job is not wedged. Per-method values override it.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "55s")
public class SchedulerLockConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLockConfig.class);

    @Bean
    public LockProvider lockProvider(ObjectProvider<RedisConnectionFactory> redisFactory) {
        RedisConnectionFactory cf = redisFactory.getIfAvailable();
        if (cf == null) {
            log.info("No RedisConnectionFactory bean — ShedLock in no-op mode "
                    + "(locks always granted; single-node assumed, jobs' atomic guards prevent double work)");
            return new NoOpLockProvider();
        }
        log.info("ShedLock active — Redis-backed lock for @Scheduled jobs (env=flashsale)");
        return new RedisLockProvider(cf, "flashsale");
    }

    /**
     * Always-grant lock provider used when no Redis is present. Each lock is
     * acquired immediately and unlock is a no-op, so jobs run on every tick.
     * {@link SimpleLock} has a single abstract method ({@code unlock()}), so
     * the granted lock is a no-op lambda; {@code extend(...)} is a default
     * method ShedLock never needs to call for this provider.
     */
    static final class NoOpLockProvider implements LockProvider {
        @Override
        public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
            return Optional.of(() -> { /* no-op unlock */ });
        }
    }
}
