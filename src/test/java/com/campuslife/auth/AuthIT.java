package com.campuslife.auth;

import com.campuslife.support.IntegrationSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AuthIT extends IntegrationSupport {
    private static final String PHONE = "13800000001";

    @Test
    void issuedOtpLogsInDatabaseAccountAndExposesOnlyItsIdentity() {
        String code = issueCode(PHONE);
        assertThat(redis.getExpire(codeKey(PHONE), TimeUnit.MILLISECONDS)).isBetween(100_000L, 120_000L);

        ResponseEntity<JsonNode> loggedIn = loginWithCode(PHONE, code);

        assertThat(loggedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loggedIn.getHeaders().getCacheControl()).contains("no-store");
        JsonNode data = loggedIn.getBody().path("data");
        assertThat(data.path("expiresInSeconds").asLong()).isEqualTo(7200);
        assertThat(data.path("user").path("id").asLong()).isEqualTo(1);
        assertThat(data.path("user").path("role").asText()).isEqualTo("USER");
        assertThat(redis.hasKey(codeKey(PHONE))).isFalse();
        ResponseEntity<JsonNode> me = me(data.path("token").asText());
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().path("data").path("id").asLong()).isEqualTo(1);
        assertThat(me.getBody().path("data").has("phone")).isFalse();
        assertThat(me.getBody().path("data").has("token")).isFalse();
        assertThat(loginWithCode(PHONE, code).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void fifthWrongAttemptDestroysOtpAndCorrectCodeCannotReviveIt() {
        String code = issueCode(PHONE);
        String wrongCode = code.equals("000000") ? "000001" : "000000";
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(loginWithCode(PHONE, wrongCode).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(redis.opsForHash().get(codeKey(PHONE), "attempts")).isEqualTo("4");
        assertThat(loginWithCode(PHONE, wrongCode).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(redis.hasKey(codeKey(PHONE))).isFalse();
        assertThat(loginWithCode(PHONE, code).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void correctCodeStillWorksAfterFourWrongAttempts() {
        String code = issueCode(PHONE);
        String wrongCode = code.equals("000000") ? "000001" : "000000";
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(loginWithCode(PHONE, wrongCode).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(loginWithCode(PHONE, code).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void sixteenConcurrentLoginsCanConsumeOneOtpOnlyOnce() throws Exception {
        String code = issueCode(PHONE);
        int workers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> pending = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                pending.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent login start barrier timed out");
                    }
                    return loginWithCode(PHONE, code);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = 0;
            int rejected = 0;
            for (Future<ResponseEntity<JsonNode>> future : pending) {
                ResponseEntity<JsonNode> response = future.get(30, TimeUnit.SECONDS);
                if (response.getStatusCode().equals(HttpStatus.OK)) {
                    successes++;
                } else {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(response.getBody().path("code").asText()).isEqualTo("INVALID_CODE");
                    rejected++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(rejected).isEqualTo(workers - 1);
            assertThat(redis.hasKey(codeKey(PHONE))).isFalse();
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void protectedEndpointsRejectMissingOrMalformedToken() {
        ResponseEntity<JsonNode> missing = http.getForEntity("/api/auth/me", JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(missing.getBody().path("code").asText()).isEqualTo("AUTH_REQUIRED");
        assertThat(me("bad-token").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(me("a".repeat(43)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutInvalidatesOnlyCurrentSessionAndIsIdempotent() {
        String firstToken = login(PHONE);
        String secondToken = login(PHONE);
        ResponseEntity<JsonNode> logout = http.exchange("/api/auth/logout", HttpMethod.POST,
                new HttpEntity<>(null, auth(firstToken)), JsonNode.class);

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me(firstToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(me(secondToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.exchange("/api/auth/logout", HttpMethod.POST,
                new HttpEntity<>(null, auth(firstToken)), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void readingSessionDoesNotExtendItsRemainingLifetime() throws Exception {
        String token = login(PHONE);
        String key = redisPrefix + "auth:session:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        assertThat(redis.expire(key, Duration.ofSeconds(30))).isTrue();
        Long before = redis.getExpire(key, TimeUnit.MILLISECONDS);

        assertThat(me(token).getStatusCode()).isEqualTo(HttpStatus.OK);

        Long after = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(after).isPositive().isLessThanOrEqualTo(before).isLessThanOrEqualTo(30_000L);
    }

    @Test
    void invalidPhoneAndUnknownAccountDoNotCreateUsers() {
        assertThat(http.postForEntity("/api/auth/code", Map.of("phone", "123"), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/api/auth/code", Map.of("phone", "13899999999"), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(4);
    }

    private String issueCode(String phone) {
        ResponseEntity<JsonNode> response = http.postForEntity("/api/auth/code", Map.of("phone", phone), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        String code = response.getBody().path("data").path("code").asText();
        assertThat(code.length()).isEqualTo(6);
        return code;
    }

    private ResponseEntity<JsonNode> loginWithCode(String phone, String code) {
        return http.postForEntity("/api/auth/login", Map.of("phone", phone, "code", code), JsonNode.class);
    }

    private ResponseEntity<JsonNode> me(String token) {
        return http.exchange("/api/auth/me", HttpMethod.GET, new HttpEntity<>(auth(token)), JsonNode.class);
    }

    private String codeKey(String phone) {
        return redisPrefix + "auth:code:{" + phone + "}";
    }
}
