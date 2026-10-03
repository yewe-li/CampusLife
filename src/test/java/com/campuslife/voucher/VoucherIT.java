package com.campuslife.voucher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campuslife.support.IntegrationSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** Real HTTP requests, Redis sessions, and MySQL transactions; no mocked persistence. */
class VoucherIT extends IntegrationSupport {
    @Test
    @Timeout(180)
    void sixtyFourUsersCompeteWithThirtyTwoWorkersForTwentyCoupons() throws Exception {
        List<Object[]> accounts = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            accounts.add(new Object[] {1000L + i, Long.toString(13600000000L + i), "并发用户" + i});
        }
        jdbc.batchUpdate("INSERT INTO users(id,phone,nickname,role) VALUES(?,?,?,'USER')", accounts);
        jdbc.update("UPDATE voucher_stock SET stock=20, initial_stock=20 WHERE voucher_id=1");
        List<String> tokens = new ArrayList<>();
        for (Object[] account : accounts) {
            tokens.add(login((String) account[1]));
        }

        List<ResponseEntity<JsonNode>> responses = concurrentClaims(tokens, 32);

        int successes = 0;
        int soldOut = 0;
        for (ResponseEntity<JsonNode> response : responses) {
            if (response.getStatusCode().is2xxSuccessful()) {
                assertEquals("OK", body(response).path("code").asText());
                successes++;
            } else {
                assertEquals(409, response.getStatusCode().value());
                assertEquals("SOLD_OUT", body(response).path("code").asText());
                soldOut++;
            }
        }
        assertEquals(20, successes);
        assertEquals(44, soldOut);
        assertEquals(0, stock(1));
        assertEquals(20, count("SELECT COUNT(*) FROM voucher_orders WHERE voucher_id=1"));
        assertEquals(20, count("SELECT COUNT(DISTINCT user_id) FROM voucher_orders WHERE voucher_id=1"));
        assertEquals(20, count("SELECT COUNT(DISTINCT redeem_code) FROM voucher_orders WHERE voucher_id=1"));
        assertEquals(0, count("SELECT COUNT(*) FROM voucher_stock WHERE stock<0 OR stock>initial_stock"));
    }

    @Test
    @Timeout(120)
    void thirtyTwoConcurrentRequestsByOneUserConsumeExactlyOneCoupon() throws Exception {
        String token = login("13800000001");

        List<ResponseEntity<JsonNode>> responses = concurrentClaims(Collections.nCopies(32, token), 32);

        int successes = 0;
        int duplicates = 0;
        for (ResponseEntity<JsonNode> response : responses) {
            if (response.getStatusCode().is2xxSuccessful()) {
                successes++;
            } else {
                assertEquals(409, response.getStatusCode().value());
                assertEquals("ALREADY_CLAIMED", body(response).path("code").asText());
                duplicates++;
            }
        }
        assertEquals(1, successes);
        assertEquals(31, duplicates);
        assertEquals(99, stock(1));
        assertEquals(1, count("SELECT COUNT(*) FROM voucher_orders WHERE user_id=1 AND voucher_id=1"));
        assertEquals(1, count("SELECT COUNT(*) FROM voucher_orders"));
    }

    @Test
    @Timeout(60)
    void mysqlInsertFailureRollsBackAlreadyDecrementedStockAndAllowsRetry() throws Exception {
        assertEquals("campuslife_test", jdbc.queryForObject("SELECT DATABASE()", String.class),
                "The intentional failure trigger must only run in campuslife_test");
        String token = login("13800000001");
        assertEquals(100, stock(1));
        jdbc.execute("""
                CREATE TRIGGER campuslife_test.voucher_it_reject_insert
                BEFORE INSERT ON campuslife_test.voucher_orders FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'voucher-it-forced-insert-failure'
                """);
        try {
            ResponseEntity<JsonNode> failed = claim(token, 1);
            assertEquals(503, failed.getStatusCode().value());
            assertEquals("DEPENDENCY_UNAVAILABLE", body(failed).path("code").asText());
            assertFalse(body(failed).toString().contains("voucher-it-forced-insert-failure"));
            assertEquals(100, stock(1));
            assertEquals(0, count("SELECT COUNT(*) FROM voucher_orders"));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS campuslife_test.voucher_it_reject_insert");
        }

        assertEquals(200, claim(token, 1).getStatusCode().value());
        assertEquals(99, stock(1));
        assertEquals(1, count("SELECT COUNT(*) FROM voucher_orders WHERE user_id=1 AND voucher_id=1"));
    }

    @Test
    @Timeout(60)
    void rejectsExpiredAndOfflineOffersWithoutConsumingInventory() throws Exception {
        String token = login("13800000001");
        ResponseEntity<JsonNode> expired = claim(token, 3);
        assertEquals(409, expired.getStatusCode().value());
        assertEquals("VOUCHER_NOT_ACTIVE", body(expired).path("code").asText());
        assertEquals(10, stock(3));
        JsonNode expiredList = body(http.getForEntity("/api/shops/3/vouchers", JsonNode.class)).path("data");
        assertEquals(0, expiredList.path("total").asLong());
        assertEquals(0, expiredList.path("items").size());

        jdbc.update("UPDATE shops SET status=0 WHERE id=1");
        ResponseEntity<JsonNode> offline = claim(token, 1);
        assertEquals(404, offline.getStatusCode().value());
        assertEquals("SHOP_NOT_FOUND", body(offline).path("code").asText());
        assertEquals(100, stock(1));
        assertEquals(404, http.getForEntity("/api/shops/1/vouchers", JsonNode.class).getStatusCode().value());
        assertEquals(0, count("SELECT COUNT(*) FROM voucher_orders"));
    }

    @Test
    @Timeout(60)
    void privatePaginationStaysIsolatedAndKeepsHistoricalRightsWhenShopGoesOffline() throws Exception {
        String firstToken = login("13800000001");
        String secondToken = login("13800000002");
        JsonNode firstCoffee = successfulClaim(firstToken, 1);
        JsonNode firstLunch = successfulClaim(firstToken, 2);
        JsonNode secondCoffee = successfulClaim(secondToken, 1);
        assertNotEquals(firstCoffee.path("redeemCode").asText(), secondCoffee.path("redeemCode").asText());

        // Force equal timestamps to exercise the id tie-breaker instead of relying on clock resolution.
        jdbc.update("UPDATE voucher_orders SET created_at='2026-09-30 01:00:00' WHERE user_id=1");
        List<String> firstIds = new ArrayList<>(List.of(firstCoffee.path("id").asText(), firstLunch.path("id").asText()));
        firstIds.sort(Collections.reverseOrder());
        JsonNode pageOne = mine(firstToken, "?page=1&size=1&userId=2");
        JsonNode pageTwo = mine(firstToken, "?page=2&size=1&userId=2");
        assertEquals(2, pageOne.path("total").asLong());
        assertEquals(1, pageOne.path("items").size());
        assertEquals(1, pageTwo.path("items").size());
        assertEquals(firstIds.get(0), pageOne.path("items").get(0).path("id").asText());
        assertEquals(firstIds.get(1), pageTwo.path("items").get(0).path("id").asText());
        assertFalse(pageOne.toString().contains(secondCoffee.path("redeemCode").asText()));
        assertFalse(pageTwo.toString().contains(secondCoffee.path("redeemCode").asText()));

        JsonNode secondPage = mine(secondToken, "?userId=1");
        assertEquals(1, secondPage.path("total").asLong());
        assertEquals(secondCoffee.path("id").asText(), secondPage.path("items").get(0).path("id").asText());
        assertEquals(secondCoffee.path("redeemCode").asText(), secondPage.path("items").get(0).path("redeemCode").asText());
        assertFalse(secondPage.toString().contains(firstCoffee.path("redeemCode").asText()));

        JsonNode publicList = body(http.getForEntity("/api/shops/1/vouchers", JsonNode.class));
        assertFalse(publicList.toString().contains("redeemCode"));
        assertFalse(publicList.toString().contains(firstCoffee.path("redeemCode").asText()));
        jdbc.update("UPDATE shops SET status=0 WHERE id=1");
        jdbc.update("UPDATE vouchers SET use_end='2037-02-01' WHERE id=1");

        JsonNode afterOffline = mine(firstToken, "");
        assertEquals(2, afterOffline.path("total").asLong());
        JsonNode historicalCoffee = null;
        for (JsonNode item : afterOffline.path("items")) {
            if (item.path("voucherId").asLong() == 1) {
                historicalCoffee = item;
            }
        }
        assertNotNull(historicalCoffee);
        assertEquals(firstCoffee.path("expiresAt").asText(), historicalCoffee.path("expiresAt").asText());
        assertEquals("课间咖啡", historicalCoffee.path("shopName").asText());
        assertEquals("咖啡满20减5", historicalCoffee.path("voucherTitle").asText());
        assertEquals(401, http.getForEntity("/api/me/vouchers", JsonNode.class).getStatusCode().value());
        assertEquals(401, http.postForEntity("/api/vouchers/1/claims", HttpEntity.EMPTY, JsonNode.class).getStatusCode().value());
        assertEquals(400, http.exchange("/api/me/vouchers?size=101", HttpMethod.GET,
                new HttpEntity<>(auth(firstToken)), JsonNode.class).getStatusCode().value());
    }

    private List<ResponseEntity<JsonNode>> concurrentClaims(List<String> tokens, int concurrency) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(Math.min(concurrency, tokens.size()));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        try {
            for (String token : tokens) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent claim start timed out");
                    }
                    return claim(token, 1);
                }));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS), "Claim workers did not become ready");
            start.countDown();
            List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> future : futures) {
                responses.add(future.get(45, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            start.countDown();
            for (Future<?> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "Claim workers did not terminate");
        }
    }

    private ResponseEntity<JsonNode> claim(String token, long voucherId) {
        return http.exchange("/api/vouchers/" + voucherId + "/claims", HttpMethod.POST,
                new HttpEntity<>(auth(token)), JsonNode.class);
    }

    private JsonNode successfulClaim(String token, long voucherId) {
        ResponseEntity<JsonNode> response = claim(token, voucherId);
        assertEquals(200, response.getStatusCode().value());
        return body(response).path("data");
    }

    private JsonNode mine(String token, String query) {
        ResponseEntity<JsonNode> response = http.exchange("/api/me/vouchers" + query,
                HttpMethod.GET, new HttpEntity<>(auth(token)), JsonNode.class);
        assertEquals(200, response.getStatusCode().value());
        return body(response).path("data");
    }

    private static JsonNode body(ResponseEntity<JsonNode> response) {
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private int stock(long voucherId) {
        return jdbc.queryForObject("SELECT stock FROM voucher_stock WHERE voucher_id=?", Integer.class, voucherId);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
