package com.anurag.smartdesk.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;

// Fault-tolerant cache error handler.
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
@Configuration
public class FaultTolerantCacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(
            FaultTolerantCacheConfig.class);

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
