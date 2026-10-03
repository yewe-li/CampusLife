package com.campuslife.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campuslife.support.IntegrationSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Management queries use authenticated ownership and include offline shops. */
class MerchantShopIT extends IntegrationSupport {
    @Test
    void managementRequiresLoginAndMerchantRole() {
        String ordinaryUser = login("13800000001");
        for (String path : List.of("/api/merchant/shops", "/api/merchant/shops/1")) {
            assertError(http.getForEntity(path, JsonNode.class), HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
            assertError(get(path, ordinaryUser), HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    @Test
    void merchantPagesIncludeOfflineShopsAndCannotSelectAnotherMerchant() {
        String owner = login("13900000002");
        JsonNode first = data(get("/api/merchant/shops?page=1&size=1", owner));
        JsonNode second = data(get("/api/merchant/shops?page=2&size=1", owner));
        JsonNode pastEnd = data(get("/api/merchant/shops?page=3&size=1", owner));

        assertEquals(List.of(2L), ids(first));
        assertEquals(List.of(4L), ids(second));
        assertTrue(pastEnd.path("items").isEmpty());
        assertEquals(0, second.path("items").get(0).path("status").asInt());
        for (JsonNode page : List.of(first, second, pastEnd)) {
            assertEquals(2, page.path("total").asInt());
            assertEquals(1, page.path("size").asInt());
        }
        assertEquals(1, first.path("page").asInt());
        assertEquals(2, second.path("page").asInt());
        assertEquals(3, pastEnd.path("page").asInt());
        assertEquals(ids(first), ids(data(get("/api/merchant/shops?page=1&size=1", owner))));

        // An extra client-supplied owner ID never controls the database filter.
        JsonNode attemptedOverride = data(get("/api/merchant/shops?merchantId=101&userId=101", owner));
        assertEquals(List.of(2L, 4L), ids(attemptedOverride));
        assertEquals(2, attemptedOverride.path("total").asInt());
        String eastMerchant = login("13900000001");
        assertEquals(List.of(1L, 3L), ids(data(get("/api/merchant/shops", eastMerchant))));
    }

    @Test
    void merchantDetailAllowsOnlyOwnShopAndOmitsOwnershipField() {
        String owner = login("13900000002");
        JsonNode offline = data(get("/api/merchant/shops/4", owner));
        assertEquals(4L, offline.path("id").asLong());
        assertEquals(0, offline.path("status").asInt());
        assertEquals(0, offline.path("version").asInt());
        assertFalse(offline.has("merchantId"));

        assertError(get("/api/merchant/shops/1", owner), HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertError(get("/api/merchant/shops/999", owner), HttpStatus.NOT_FOUND, "SHOP_NOT_FOUND");
        assertError(patch(1L, owner, Map.of("status", 0, "version", 0)),
                HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertEquals(1, jdbc.queryForObject("SELECT status FROM shops WHERE id=1", Integer.class));
    }

    @Test
    void invalidManagementPaginationAndIdsReturnBadRequest() {
        String owner = login("13900000001");
        for (String query : List.of("page=0", "page=-1", "page=1000001", "size=0", "size=-1",
                "size=101", "page=not-a-number")) {
            assertError(get("/api/merchant/shops?" + query, owner),
                    HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
        }
        for (String id : List.of("0", "-1", "not-a-number")) {
            assertError(get("/api/merchant/shops/" + id, owner),
                    HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
        }
        JsonNode maximumPage = data(get("/api/merchant/shops?page=1000000&size=100", owner));
        assertEquals(2, maximumPage.path("total").asInt());
        assertTrue(maximumPage.path("items").isEmpty());
    }

    @Test
    void offlineShopStaysManageableAndCanBePublishedAgain() {
        String owner = login("13900000001");
        assertEquals(HttpStatus.OK, http.getForEntity("/api/shops/1", JsonNode.class).getStatusCode());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key(1L))));

        JsonNode offline = data(patch(1L, owner, Map.of("status", 0, "version", 0)));
        assertEquals(1, offline.path("version").asInt());
        assertError(http.getForEntity("/api/shops/1", JsonNode.class),
                HttpStatus.NOT_FOUND, "SHOP_NOT_FOUND");
        assertEquals("__NULL__", redis.opsForValue().get(key(1L)));

        JsonNode managementPage = data(get("/api/merchant/shops", owner));
        assertEquals(List.of(1L, 3L), ids(managementPage));
        assertEquals(0, managementPage.path("items").get(0).path("status").asInt());
        JsonNode current = data(get("/api/merchant/shops/1", owner));
        assertEquals(0, current.path("status").asInt());
        assertEquals(1, current.path("version").asInt());
        // Private management never replaces the negative public cache with an offline shop.
        assertEquals("__NULL__", redis.opsForValue().get(key(1L)));

        JsonNode online = data(patch(1L, owner, Map.of("status", 1, "version", current.path("version").asInt())));
        assertEquals(2, online.path("version").asInt());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key(1L))));
        JsonNode publicDetail = data(http.getForEntity("/api/shops/1", JsonNode.class));
        assertEquals(1, publicDetail.path("status").asInt());
        assertEquals(2, publicDetail.path("version").asInt());
        assertEquals(List.of(1L, 2L, 3L), ids(data(http.getForEntity("/api/shops", JsonNode.class))));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM shops WHERE id=1", Integer.class));
    }

    @Test
    void managementReadsDatabaseEvenWhenPublicCacheIsStale() {
        String owner = login("13900000001");
        JsonNode initial = data(http.getForEntity("/api/shops/1", JsonNode.class));
        // Only the isolated test database is changed; bypass eviction to demonstrate cache independence.
        jdbc.update("UPDATE shops SET name='数据库中的新店名', version=version+1 WHERE id=1");

        JsonNode management = data(get("/api/merchant/shops/1", owner));
        assertEquals("数据库中的新店名", management.path("name").asText());
        assertEquals(1, management.path("version").asInt());
        JsonNode publicDetail = data(http.getForEntity("/api/shops/1", JsonNode.class));
        assertEquals(initial.path("name").asText(), publicDetail.path("name").asText());
        assertEquals(0, publicDetail.path("version").asInt());
    }

    private ResponseEntity<JsonNode> get(String path, String token) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET,
                new HttpEntity<>(auth(token)), JsonNode.class);
        assertEquals("no-store", response.getHeaders().getCacheControl());
        return response;
    }

    private ResponseEntity<JsonNode> patch(long id, String token, Map<String, ?> body) {
        return http.exchange("/api/shops/" + id, HttpMethod.PATCH,
                new HttpEntity<>(body, auth(token)), JsonNode.class);
    }

    private static JsonNode data(ResponseEntity<JsonNode> response) {
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
}
