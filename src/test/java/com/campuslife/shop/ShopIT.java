package com.campuslife.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campuslife.support.IntegrationSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Real HTTP, MySQL and Redis checks; IntegrationSupport resets shared fixtures per test. */
class ShopIT extends IntegrationSupport {
    @Value("${app.redis-prefix}")
    private String redisPrefix;

    @Test
    void filtersApplyInclusivePriceBoundaryAndReturnEmptyForUnknownCampus() {
        JsonNode equalBudget = getData("/api/shops?campus={campus}&category={category}&maxPrice={price}",
                "东校区", "咖啡", 1800);
        assertEquals(1, equalBudget.path("total").asInt());
        assertEquals(List.of(1L), ids(equalBudget));

        JsonNode belowBudget = getData("/api/shops?campus={campus}&category={category}&maxPrice={price}",
                "东校区", "咖啡", 1799);
        assertEquals(0, belowBudget.path("total").asInt());
        assertTrue(belowBudget.path("items").isEmpty());

        JsonNode combinedFilter = getData("/api/shops?campus={campus}&category={category}&maxPrice={price}",
                "东校区", "餐饮", 1500);
        assertEquals(List.of(3L), ids(combinedFilter));
        assertEquals(1, combinedFilter.path("total").asInt());

        JsonNode unknownCampus = getData("/api/shops?campus={campus}", "不存在的校区");
        assertEquals(0, unknownCampus.path("total").asInt());
        assertTrue(unknownCampus.path("items").isEmpty());
    }

    @Test
    void stablePagesAndCountShareFiltersAndExcludeOfflineShops() {
        JsonNode first = getData("/api/shops?page=1&size=2");
        JsonNode second = getData("/api/shops?page=2&size=2");
        JsonNode pastEnd = getData("/api/shops?page=3&size=2");

        assertEquals(List.of(1L, 2L), ids(first));
        assertEquals(List.of(3L), ids(second));
        assertEquals(List.of(), ids(pastEnd));
        for (JsonNode page : List.of(first, second, pastEnd)) {
            assertEquals(3, page.path("total").asInt());
            assertEquals(2, page.path("size").asInt());
        }
        assertEquals(1, first.path("page").asInt());
        assertEquals(2, second.path("page").asInt());
        assertEquals(3, pastEnd.path("page").asInt());
        assertEquals(ids(first), ids(getData("/api/shops?page=1&size=2")));

        JsonNode eastSecond = getData("/api/shops?campus={campus}&page=2&size=1", "东校区");
        assertEquals(List.of(3L), ids(eastSecond));
        assertEquals(2, eastSecond.path("total").asInt());
    }

    @Test
    void invalidQueryBoundsReturnBadRequest() {
        for (String query : List.of("page=0", "page=-1", "page=1000001", "size=0", "size=-1",
                "size=101", "maxPrice=-1", "maxPrice=10000001", "page=not-a-number")) {
            assertError(http.getForEntity("/api/shops?" + query, JsonNode.class),
                    HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
        }
        assertError(http.getForEntity("/api/shops/0", JsonNode.class),
                HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
    }

    @Test
    void ordinaryUserAndOtherMerchantCannotModifyShop() {
        String user = login("13800000001");
        String otherMerchant = login("13900000002");

        assertError(patch(1L, user, Map.of("name", "越权修改", "version", 0)),
                HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertError(patch(1L, otherMerchant, Map.of("name", "越权修改", "version", 0)),
                HttpStatus.FORBIDDEN, "FORBIDDEN");

        assertEquals("课间咖啡", jdbc.queryForObject("SELECT name FROM shops WHERE id=1", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT version FROM shops WHERE id=1", Integer.class));
    }

    @Test
    void invalidPatchAndOwnershipFieldsAreRejectedWithoutChangingDatabase() {
        String owner = login("13900000001");
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(
                Map.of("name", "缺少版本"),
                Map.of("version", 0),
                Map.of("name", "  ", "version", 0),
                Map.of("averagePrice", -1, "version", 0),
                Map.of("averagePrice", 10_000_001, "version", 0),
                Map.of("status", 2, "version", 0),
                Map.of("name", "非法版本", "version", -1),
                Map.of("name", "非法归属", "merchantId", 102, "version", 0))) {
            assertError(patch(1L, owner, invalid), HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
        }
        assertEquals(101L, jdbc.queryForObject("SELECT merchant_id FROM shops WHERE id=1", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT version FROM shops WHERE id=1", Integer.class));
    }

    @Test
    void concurrentUpdatesWithSameVersionHaveOneWinnerAndOneConflict() throws Exception {
        String owner = login("13900000001");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of("并发名称甲", "并发名称乙").stream().map(name -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent update start timed out");
                }
                return patch(1L, owner, Map.of("name", name, "version", 0));
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var first = futures.get(0).get(20, TimeUnit.SECONDS);
            var second = futures.get(1).get(20, TimeUnit.SECONDS);

            assertEquals(List.of(200, 409), List.of(first.getStatusCode().value(), second.getStatusCode().value())
                    .stream().sorted().toList());
            var winner = first.getStatusCode().is2xxSuccessful() ? first : second;
            var loser = first.getStatusCode().is2xxSuccessful() ? second : first;
            assertError(loser, HttpStatus.CONFLICT, "VERSION_CONFLICT");
            assertNotNull(winner.getBody());
            assertEquals(1, winner.getBody().path("data").path("version").asInt());
            assertEquals(winner.getBody().path("data").path("name").asText(),
                    jdbc.queryForObject("SELECT name FROM shops WHERE id=1", String.class));
            assertEquals(1, jdbc.queryForObject("SELECT version FROM shops WHERE id=1", Integer.class));
        } finally {
            start.countDown();
        }
        assertError(patch(1L, owner, Map.of("name", "过期版本", "version", 0)),
                HttpStatus.CONFLICT, "VERSION_CONFLICT");
        assertEquals(1, jdbc.queryForObject("SELECT version FROM shops WHERE id=1", Integer.class));
    }

    @Test
    void committedUpdatesEvictCacheAndOfflineShopBecomesNotFound() {
        String owner = login("13900000001");
        JsonNode initial = getData("/api/shops/1");
        assertEquals(0, initial.path("version").asInt());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key(1L))));

        var renamed = patch(1L, owner, Map.of("name", "课间咖啡新店名", "averagePrice", 1900, "version", 0));
        assertEquals(HttpStatus.OK, renamed.getStatusCode());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(1L))));
        assertEquals("课间咖啡新店名", jdbc.queryForObject("SELECT name FROM shops WHERE id=1", String.class));
        assertEquals(1900, jdbc.queryForObject("SELECT average_price FROM shops WHERE id=1", Integer.class));

        JsonNode refreshed = getData("/api/shops/1");
        assertEquals("课间咖啡新店名", refreshed.path("name").asText());
        assertEquals(1, refreshed.path("version").asInt());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key(1L))));

        var offline = patch(1L, owner, Map.of("status", 0, "version", 1));
        assertEquals(HttpStatus.OK, offline.getStatusCode());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(1L))));
        assertError(http.getForEntity("/api/shops/1", JsonNode.class),
                HttpStatus.NOT_FOUND, "SHOP_NOT_FOUND");
        assertEquals(List.of(2L, 3L), ids(getData("/api/shops")));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM shops WHERE id=1", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT version FROM shops WHERE id=1", Integer.class));
    }

    @Test
    void redisStoresOnlyPublicDetailWithNormalAndNegativeTtls() throws Exception {
        JsonNode publicDetail = getData("/api/shops/1");
        String serialized = redis.opsForValue().get(key(1L));
        assertNotNull(serialized);
        JsonNode cached = json.readTree(serialized);
        assertEquals("课间咖啡", cached.path("name").asText());
        assertFalse(cached.has("merchantId"));
        assertFalse(cached.has("redeemCode"));
        assertTtl(1L, 290L, 330L);

        // Direct database mutation deliberately bypasses eviction to prove the second HTTP read hits Redis.
        jdbc.update("UPDATE shops SET name='数据库中的另一个名称' WHERE id=1");
        assertEquals(publicDetail.path("name").asText(), getData("/api/shops/1").path("name").asText());

        for (long id : List.of(4L, 999L)) {
            assertError(http.getForEntity("/api/shops/" + id, JsonNode.class),
                    HttpStatus.NOT_FOUND, "SHOP_NOT_FOUND");
            assertEquals("__NULL__", redis.opsForValue().get(key(id)));
            assertTtl(id, 20L, 30L);
        }
    }

    @Test
    void invalidRedisJsonIsReplacedWithDatabaseDetail() throws Exception {
        redis.opsForValue().set(key(1L), "{\"id\":1}", Duration.ofMinutes(5));

        JsonNode result = getData("/api/shops/1");

        assertEquals("课间咖啡", result.path("name").asText());
        String repaired = redis.opsForValue().get(key(1L));
        assertNotNull(repaired);
        assertEquals("课间咖啡", json.readTree(repaired).path("name").asText());
    }

    private ResponseEntity<JsonNode> patch(long id, String token, Map<String, ?> body) {
        return http.exchange("/api/shops/" + id, HttpMethod.PATCH,
                new HttpEntity<>(body, auth(token)), JsonNode.class);
    }

    private JsonNode getData(String path, Object... variables) {
        ResponseEntity<JsonNode> response = http.getForEntity(path, JsonNode.class, variables);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("OK", response.getBody().path("code").asText());
        return response.getBody().path("data");
    }

    private static List<Long> ids(JsonNode page) {
        return StreamSupport.stream(page.path("items").spliterator(), false)
                .map(item -> item.path("id").asLong()).toList();
    }

    private static void assertError(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertEquals(status, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(code, response.getBody().path("code").asText());
    }

    private String key(long id) {
        return redisPrefix + "shop:detail:" + id;
    }

    private void assertTtl(long id, long minimum, long maximum) {
        Long ttl = redis.getExpire(key(id), TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl >= minimum && ttl <= maximum, "Unexpected cache TTL: " + ttl);
    }
}
