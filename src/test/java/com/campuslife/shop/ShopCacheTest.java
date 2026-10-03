package com.campuslife.shop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ShopCacheTest {
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    @Mock private ShopMapper mapper;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private SimpleMeterRegistry metrics;
    private ShopCache cache;

    @BeforeEach
    void setUp() {
        metrics = new SimpleMeterRegistry();
        cache = new ShopCache(redis, json, metrics, "test:");
    }

    @Test
    void redisReadFailureFallsBackToDatabaseWithoutAnotherCacheCall() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("test:shop:detail:1")).thenThrow(new RedisConnectionFailureException("private detail"));
        when(mapper.findOnline(1L)).thenReturn(shop());

        ShopView result = new ShopService(mapper, cache, Clock.systemUTC()).detail(1L);

        assertEquals("咖啡店", result.name());
        assertEquals(1.0, metric("fallback"));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void malformedJsonReloadsPublicDataAndStoresWithBoundedJitter() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("test:shop:detail:1")).thenReturn("{\"id\":1}");
        when(mapper.findOnline(1L)).thenReturn(shop());

        ShopView result = new ShopService(mapper, cache, Clock.systemUTC()).detail(1L);

        assertEquals(1L, result.id());
        assertEquals(1.0, metric("miss"));
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).set(eq("test:shop:detail:1"), stored.capture(), ttl.capture());
        assertFalse(stored.getValue().contains("merchantId"));
        assertTrue(ttl.getValue().getSeconds() >= 300 && ttl.getValue().getSeconds() <= 330);
    }

    @Test
    void missingShopUsesThirtySecondNegativeCache() {
        when(redis.opsForValue()).thenReturn(values);

        cache.store(999L, null);
        verify(values).set("test:shop:detail:999", "__NULL__", Duration.ofSeconds(30));
        when(values.get("test:shop:detail:999")).thenReturn("__NULL__");
        ShopCache.Lookup result = cache.lookup(999L);

        assertTrue(result.hit());
        assertNull(result.detail());
        assertEquals(1.0, metric("hit"));
    }

    @Test
    void failedPostCommitInvalidationDoesNotThrow() {
        when(redis.delete("test:shop:detail:1")).thenThrow(new RedisConnectionFailureException("private detail"));

        assertDoesNotThrow(() -> cache.evict(1L));

        assertEquals(1.0, metric("fallback"));
    }

    @Test
    void updatePayloadCannotIncludeAnOwnershipChange() {
        assertThrows(JsonProcessingException.class, () -> json.readValue(
                "{\"name\":\"咖啡店\",\"version\":2,\"merchantId\":102}", ShopUpdateRequest.class));
    }

    private double metric(String result) {
        return metrics.get("campuslife.shop.cache").tag("result", result).counter().count();
    }

    private static ShopRow shop() {
        return new ShopRow(1L, 101L, "咖啡店", "东校区", "咖啡", 1800,
                1, 2, "校园咖啡", "一号楼", LocalDateTime.of(2026, 1, 1, 0, 0));
    }
}
