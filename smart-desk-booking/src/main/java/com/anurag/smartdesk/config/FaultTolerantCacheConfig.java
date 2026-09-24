package com.anurag.smartdesk.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.Map;

// Fault-tolerant cache configuration with per-cache TTLs.
//
// WHY DO WE NEED THIS?
// Redis is an external service that can go down (network issues,
// Redis restart, memory exhaustion). Without this handler, a Redis
// failure would crash our entire booking API with 500 errors.
//
// With this handler, when Redis fails:
//   1. The error is logged as a WARNING (not thrown)
//   2. The app falls through to PostgreSQL (slower but works)
//   3. Users never see any error — they just get slightly slower responses
//
// This is the "cache-aside with graceful degradation" pattern.
//
// WHAT WE CACHE (and why):
//   "floors"         → Floor metadata (name, capacity, center).  TTL 30 min.
//   "floor-desks"    → Desk layout/coordinates on a floor.       TTL 30 min.
//   "teams"          → Team name/metadata.                       TTL 15 min.
//   "employee-team"  → Employee → team mapping.                  TTL 15 min.
//
// WHAT WE DO NOT CACHE:
//   Desk availability, active booking counts, quota remaining,
//   or anything involved in transactional booking state.
//   Those MUST always hit PostgreSQL under pessimistic locks.
@Configuration
@EnableCaching
public class FaultTolerantCacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(
            FaultTolerantCacheConfig.class);

    private static final Duration TTL_30_MIN = Duration.ofMinutes(30);
    private static final Duration TTL_15_MIN = Duration.ofMinutes(15);

    /**
     * Registers a RedisCacheManager with differentiated TTLs per cache.
     *
     * <p>Floor and desk layout data changes only when admins modify
     * the physical office → 30-minute TTL is generous and safe.
     * Team and employee-team mappings change on team transfers
     * → 15-minute TTL balances freshness vs. DB load.</p>
     */
    @Bean
    public CacheManager cacheManager(
            RedisConnectionFactory connectionFactory) {

        RedisCacheConfiguration defaultConfig =
                RedisCacheConfiguration.defaultCacheConfig()
                        .disableCachingNullValues()
                        .serializeValuesWith(
                                org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair
                                        .fromSerializer(new org.springframework.data.redis.serializer.JdkSerializationRedisSerializer(
                                                Thread.currentThread().getContextClassLoader())));

        // Per-cache TTL overrides
        Map<String, RedisCacheConfiguration> perCacheConfig = Map.of(
                "floors",
                defaultConfig.entryTtl(TTL_30_MIN),
                "floor-desks",
                defaultConfig.entryTtl(TTL_30_MIN),
                "teams",
                defaultConfig.entryTtl(TTL_15_MIN),
                "employee-team",
                defaultConfig.entryTtl(TTL_15_MIN)
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig.entryTtl(TTL_15_MIN))
                .withInitialCacheConfigurations(perCacheConfig)
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException e,
                                            Cache cache, Object key) {
                log.warn("Cache GET failed [{}:{}]: {}",
                        cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e,
                                            Cache cache, Object key,
                                            Object value) {
                log.warn("Cache PUT failed [{}:{}]: {}",
                        cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e,
                                              Cache cache, Object key) {
                log.warn("Cache EVICT failed [{}:{}]: {}",
                        cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e,
                                              Cache cache) {
                log.warn("Cache CLEAR failed [{}]: {}",
                        cache.getName(), e.getMessage());
            }
        };
    }
}
