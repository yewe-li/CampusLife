package com.campuslife.voucher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campuslife.support.IntegrationSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** Real HTTP, Redis identities and MySQL row locks, with a fixed clock for exact expiry checks. */
@Import(VoucherRedemptionIT.FixedTime.class)
class VoucherRedemptionIT extends IntegrationSupport {
    private static final String ENDPOINT = "/api/merchant/vouchers/redemptions";
    private static final Instant INSTANT = Instant.parse("2026-10-03T04:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(INSTANT, ZoneOffset.UTC);

    @Test
    @Timeout(60)
    void merchantRedemptionUpdatesOnlyOwnersHistoryAndDoesNotExposePrivateIdentity() {
        String first = login("13800000001");
        String second = login("13800000002");
        String merchant = login("13900000001");
        JsonNode firstClaim = claim(first, 1);
        JsonNode secondClaim = claim(second, 1);
        JsonNode issued = mine(first).path("items").get(0);
        assertEquals("ISSUED", issued.path("status").asText());
        assertTrue(issued.path("redeemedAt").isNull());

        ResponseEntity<JsonNode> response = redeem(merchant, firstClaim.path("redeemCode").asText());
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        JsonNode receipt = body(response).path("data");
        assertEquals(firstClaim.path("id"), receipt.path("id"));
        assertEquals("REDEEMED", receipt.path("status").asText());
        assertEquals(1, receipt.path("shopId").asLong());
        assertFalse(receipt.has("userId"));
        assertFalse(receipt.has("phone"));
        assertFalse(receipt.has("redeemCode"));
        assertEquals(NOW, LocalDateTime.parse(receipt.path("redeemedAt").asText()));

        JsonNode ownHistory = mine(first);
        JsonNode ownRecord = ownHistory.path("items").get(0);
        assertEquals("REDEEMED", ownRecord.path("status").asText());
        assertEquals(receipt.path("redeemedAt"), ownRecord.path("redeemedAt"));
        assertFalse(ownHistory.toString().contains(secondClaim.path("redeemCode").asText()));
        JsonNode otherHistory = mine(second);
        assertEquals("ISSUED", otherHistory.path("items").get(0).path("status").asText());
        assertFalse(otherHistory.toString().contains(firstClaim.path("redeemCode").asText()));
        assertEquals(98, count("SELECT stock FROM voucher_stock WHERE voucher_id=1"));
        assertEquals(101, count("SELECT redeemed_by FROM voucher_orders WHERE id=?", firstClaim.path("id").asText()));
    }

    @Test
    @Timeout(60)
    void rejectsUnauthenticatedUsersAndOtherMerchantsWithoutMutatingOrDisclosingClaim() {
        String user = login("13800000001");
        String wrongMerchant = login("13900000002");
        JsonNode issued = claim(user, 1);
        String code = issued.path("redeemCode").asText();
        String id = issued.path("id").asText();
        assertError(http.postForEntity(ENDPOINT, Map.of("redeemCode", code), JsonNode.class), 401, "AUTH_REQUIRED");
        assertError(redeem(user, code), 403, "FORBIDDEN");
        ResponseEntity<JsonNode> wrong = redeem(wrongMerchant, code);
        assertError(wrong, 403, "FORBIDDEN");
        assertFalse(body(wrong).toString().contains(id));
        assertFalse(body(wrong).toString().contains(code));
        assertIssued(id);
        assertEquals(99, count("SELECT stock FROM voucher_stock WHERE voucher_id=1"));
    }

    @Test
    @Timeout(120)
    void thirtyTwoConcurrentRedemptionsChangeStatusExactlyOnce() throws Exception {
        JsonNode issued = claim(login("13800000001"), 1);
        String merchant = login("13900000001");
        String code = issued.path("redeemCode").asText();
        List<ResponseEntity<JsonNode>> results = concurrentRedemptions(merchant, code, 32);
        int successes = 0;
        int duplicates = 0;
        for (ResponseEntity<JsonNode> result : results) {
            if (result.getStatusCode().value() == 200) {
                successes++;
            } else {
                assertError(result, 409, "ALREADY_REDEEMED");
                duplicates++;
            }
        }
        assertEquals(1, successes);
        assertEquals(31, duplicates);
        assertEquals(1, count("SELECT COUNT(*) FROM voucher_orders WHERE status='REDEEMED' AND redeemed_by=101"));
        assertEquals(1, count("SELECT COUNT(*) FROM voucher_orders"));
        assertEquals(99, count("SELECT stock FROM voucher_stock WHERE voucher_id=1"));
        assertEquals(NOW, jdbc.queryForObject("SELECT redeemed_at FROM voucher_orders", LocalDateTime.class));
        assertError(redeem(merchant, code), 409, "ALREADY_REDEEMED");
        assertError(redeem(login("13900000002"), code), 403, "FORBIDDEN");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    @Timeout(60)
    void expiryIsExclusiveAtMicrosecondBoundary(long microsFromNow) {
        JsonNode issued = claim(login("13800000001"), 1);
        String id = issued.path("id").asText();
        LocalDateTime expiresAt = NOW.plusNanos(microsFromNow * 1000);
        // DATETIME is a UTC wall-clock value: Timestamp.valueOf would apply the host's default zone.
        jdbc.update("UPDATE voucher_orders SET expires_at=? WHERE id=?", expiresAt, id);
        assertEquals(expiresAt, jdbc.queryForObject("SELECT expires_at FROM voucher_orders WHERE id=?",
                LocalDateTime.class, id));
        ResponseEntity<JsonNode> response = redeem(login("13900000001"), issued.path("redeemCode").asText());
        if (microsFromNow > 0) {
            assertEquals(200, response.getStatusCode().value());
        } else {
            assertError(response, 409, "VOUCHER_EXPIRED");
            assertIssued(id);
        }
        assertEquals(99, count("SELECT stock FROM voucher_stock WHERE voucher_id=1"));
    }

    @Test
    @Timeout(60)
    void offlineShopAndClosedOfferDoNotRevokeUnexpiredIssuedRights() {
        String user = login("13800000001");
        JsonNode issued = claim(user, 1);
        jdbc.update("UPDATE shops SET status=0 WHERE id=1");
        jdbc.update("UPDATE vouchers SET status=0, use_end='2037-02-01' WHERE id=1");
        assertEquals(404, http.getForEntity("/api/shops/1/vouchers", JsonNode.class).getStatusCode().value());
        assertEquals(200, redeem(login("13900000001"), issued.path("redeemCode").asText()).getStatusCode().value());
        JsonNode historical = mine(user).path("items").get(0);
        assertEquals("REDEEMED", historical.path("status").asText());
        assertEquals(issued.path("expiresAt"), historical.path("expiresAt"));
        assertEquals("课间咖啡", historical.path("shopName").asText());
    }

    @Test
    @Timeout(60)
    void rejectsMalformedAndUnknownCodesWithoutAcceptingCodeInQueryString() {
        String merchant = login("13900000001");
        for (String invalid : List.of("", "0".repeat(31), "0".repeat(33), "G".repeat(32),
                "A".repeat(32), " " + "a".repeat(32))) {
            assertError(redeem(merchant, invalid), 400, "INVALID_ARGUMENT");
        }
        assertError(http.postForEntity(ENDPOINT, new HttpEntity<>(Map.of(), auth(merchant)), JsonNode.class),
                400, "INVALID_ARGUMENT");
        assertError(redeem(merchant, "a".repeat(32)), 404, "CLAIM_NOT_FOUND");
        assertEquals(405, http.exchange(ENDPOINT + "?redeemCode=" + "a".repeat(32), HttpMethod.GET,
                new HttpEntity<>(auth(merchant)), JsonNode.class).getStatusCode().value());
        assertEquals(0, count("SELECT COUNT(*) FROM voucher_orders"));
    }

    @Test
    @Timeout(60)
    void mysqlFailureKeepsIssuedStateAndRetryCanSucceed() {
        JsonNode issued = claim(login("13800000001"), 1);
        String merchant = login("13900000001");
        String id = issued.path("id").asText();
        String code = issued.path("redeemCode").asText();
        assertEquals("campuslife_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        jdbc.execute("""
                CREATE TRIGGER campuslife_test.redemption_it_reject_update
                BEFORE UPDATE ON campuslife_test.voucher_orders FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'redemption-it-forced-update-failure'
                """);
        try {
            ResponseEntity<JsonNode> failed = redeem(merchant, code);
            assertError(failed, 503, "DEPENDENCY_UNAVAILABLE");
            assertFalse(body(failed).toString().contains("redemption-it-forced-update-failure"));
            assertFalse(body(failed).toString().contains(code));
            assertIssued(id);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS campuslife_test.redemption_it_reject_update");
        }
        assertEquals(200, redeem(merchant, code).getStatusCode().value());
    }

    @Test
    @Timeout(60)
    void schemaRejectsIncompleteRedemptionAndInvalidMerchantReference() {
        JsonNode issued = claim(login("13800000001"), 1);
        String id = issued.path("id").asText();
        assertMysqlError(3819,
                () -> jdbc.update("UPDATE voucher_orders SET status='REDEEMED' WHERE id=?", id));
        assertMysqlError(1452, () -> jdbc.update(
                "UPDATE voucher_orders SET status='REDEEMED',redeemed_at=?,redeemed_by=999999 WHERE id=?",
                NOW, id));
        assertMysqlError(3819, () -> jdbc.update(
                "UPDATE voucher_orders SET redeemed_at=? WHERE id=?", NOW, id));
        assertIssued(id);
    }

    private List<ResponseEntity<JsonNode>> concurrentRedemptions(String token, String code, int concurrency)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(15, TimeUnit.SECONDS));
                    return redeem(token, code);
                }));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            start.countDown();
            List<ResponseEntity<JsonNode>> results = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> future : futures) {
                results.add(future.get(45, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private JsonNode claim(String token, long voucherId) {
        ResponseEntity<JsonNode> response = http.postForEntity("/api/vouchers/" + voucherId + "/claims",
                new HttpEntity<>(auth(token)), JsonNode.class);
        assertEquals(200, response.getStatusCode().value());
        return body(response).path("data");
    }

    private ResponseEntity<JsonNode> redeem(String token, String code) {
        return http.postForEntity(ENDPOINT, new HttpEntity<>(Map.of("redeemCode", code), auth(token)), JsonNode.class);
    }

    private JsonNode mine(String token) {
        ResponseEntity<JsonNode> response = http.exchange("/api/me/vouchers", HttpMethod.GET,
                new HttpEntity<>(auth(token)), JsonNode.class);
        assertEquals(200, response.getStatusCode().value());
        return body(response).path("data");
    }

    private void assertIssued(String id) {
        assertEquals(1, count("SELECT COUNT(*) FROM voucher_orders WHERE id=? AND status='ISSUED'"
                + " AND redeemed_at IS NULL AND redeemed_by IS NULL", id));
    }

    private void assertMysqlError(int errorCode, org.junit.jupiter.api.function.Executable operation) {
        // CHECK errors (3819) may be translated as UncategorizedSQLException by the JDBC dialect.
        DataAccessException exception = assertThrows(DataAccessException.class, operation);
        SQLException cause = assertInstanceOf(SQLException.class, exception.getMostSpecificCause());
        assertEquals(errorCode, cause.getErrorCode());
    }

    private int count(String sql, Object... parameters) {
        Integer value = jdbc.queryForObject(sql, Integer.class, parameters);
        assertNotNull(value);
        return value;
    }

    private void assertError(ResponseEntity<JsonNode> response, int status, String code) {
        assertEquals(status, response.getStatusCode().value());
        assertEquals(code, body(response).path("code").asText());
    }

    private JsonNode body(ResponseEntity<JsonNode> response) {
        assertNotNull(response.getBody());
        return response.getBody();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean @Primary Clock redemptionClock() { return Clock.fixed(INSTANT, ZoneOffset.UTC); }
    }
}
