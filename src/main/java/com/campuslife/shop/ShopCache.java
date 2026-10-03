package com.campuslife.shop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ShopCache {
    private static final Logger log = LoggerFactory.getLogger(ShopCache.class);
    private static final String NULL_VALUE = "__NULL__";
    private static final Duration NULL_TTL = Duration.ofSeconds(30);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String prefix;
    private final Counter hit;
    private final Counter miss;
    private final Counter fallback;

    public ShopCache(StringRedisTemplate redis, ObjectMapper json, MeterRegistry registry,
                     @Value("${app.redis-prefix:campuslife:}") String prefix) {
        this.redis = redis;
        this.json = json;
        this.prefix = prefix;
        this.hit = registry.counter("campuslife.shop.cache", "result", "hit");
        this.miss = registry.counter("campuslife.shop.cache", "result", "miss");
        this.fallback = registry.counter("campuslife.shop.cache", "result", "fallback");
    }

    public Lookup lookup(long id) {
        final String value;
        try {
            value = redis.opsForValue().get(key(id));
        } catch (RuntimeException ex) {
            unavailable("read", ex);
            return new Lookup(false, false, null);
        }
        if (value == null) {
            miss.increment();
            return new Lookup(false, true, null);
        }
        if (NULL_VALUE.equals(value)) {
            hit.increment();
            return new Lookup(true, true, null);
        }
        try {
            ShopView view = json.readerFor(ShopView.class)
                    .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .readValue(value);
            if (!isValidPublicDetail(view, id)) {
                throw new IllegalArgumentException("Invalid public detail cache entry");
            }
            hit.increment();
            return new Lookup(true, true, view);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            miss.increment();
            log.warn("Shop detail cache entry is invalid; loading public data from database");
            return new Lookup(false, true, null);
        }
    }

    public void store(long id, ShopView detail) {
        try {
            String value = detail == null ? NULL_VALUE : json.writeValueAsString(detail);
            Duration ttl = detail == null ? NULL_TTL
                    : Duration.ofSeconds(300 + ThreadLocalRandom.current().nextLong(31));
            redis.opsForValue().set(key(id), value, ttl);
        } catch (JsonProcessingException | RuntimeException ex) {
            unavailable("write", ex);
        }
    }

    public void evict(long id) {
        try {
            redis.delete(key(id));
        } catch (RuntimeException ex) {
            // The database has already committed. Do not misreport a cache outage as an update failure.
            unavailable("invalidate-after-commit", ex);
        }
    }

    private boolean isValidPublicDetail(ShopView view, long id) {
        return view != null && view.id() == id && view.status() == 1 && view.version() >= 0
                && view.name() != null && !view.name().isBlank() && view.name().length() <= 100
                && view.campus() != null && view.category() != null
                && view.averagePrice() >= 0 && view.averagePrice() <= 10_000_000
                && view.updatedAt() != null;
    }

    private String key(long id) {
        return prefix + "shop:detail:" + id;
    }

    private void unavailable(String operation, Exception ex) {
        fallback.increment();
        // No exception messages, payloads, authentication headers, or Redis connection details.
        log.warn("Shop detail cache operation {} failed; database result is unaffected ({})",
                operation, ex.getClass().getSimpleName());
    }

    public record Lookup(boolean hit, boolean writable, ShopView detail) {
    }
}
