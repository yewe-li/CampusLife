package com.campuslife.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP, MySQL, and Redis fixture. Dependency failures must fail the test suite. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ContextConfiguration(initializers = IntegrationSupport.TestInfrastructureGuard.class)
public abstract class IntegrationSupport {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    @Autowired protected TestRestTemplate http;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected StringRedisTemplate redis;
    @Autowired protected ObjectMapper json;
    @Value("${app.redis-prefix}") protected String redisPrefix;

    @BeforeEach
    void resetIntegrationData() {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertThat(database).as("Only the dedicated campuslife_test database may be reset")
                .isEqualTo("campuslife_test");
        assertThat(redisPrefix).as("Only the dedicated integration-test Redis namespace may be reset")
                .isEqualTo("campuslife:test:");

        // JDK's modern HTTP client supports PATCH and bounds hung dependency/HTTP tests.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HTTP_CLIENT);
        factory.setReadTimeout(Duration.ofSeconds(20));
        http.getRestTemplate().setRequestFactory(factory);

        jdbc.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource("reset.sql"), StandardCharsets.UTF_8));
            return null;
        });

        // Gather before deleting so removing keys does not mutate the SCAN traversal.
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(redisPrefix + "*").count(100).build())) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    protected String login(String phone) {
        ResponseEntity<JsonNode> issued = http.postForEntity("/api/auth/code", Map.of("phone", phone), JsonNode.class);
        assertThat(issued.getStatusCode()).as("Demo OTP issue status").isEqualTo(HttpStatus.OK);
        assertThat(issued.getBody()).isNotNull();
        String code = issued.getBody().path("data").path("code").asText();
        assertThat(code.length()).isEqualTo(6);
        ResponseEntity<JsonNode> loggedIn = http.postForEntity("/api/auth/login",
                Map.of("phone", phone, "code", code), JsonNode.class);
        assertThat(loggedIn.getStatusCode()).as("Seed account login status").isEqualTo(HttpStatus.OK);
        assertThat(loggedIn.getBody()).isNotNull();
        String token = loggedIn.getBody().path("data").path("token").asText();
        assertThat(token.length()).isEqualTo(43);
        return token;
    }

    protected HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Runs before context refresh, so an incorrect TEST_DB_URL cannot reach Flyway or any bean. */
    public static final class TestInfrastructureGuard
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        private static final Set<String> JDBC_OPTIONS = Set.of("useUnicode", "characterEncoding",
                "connectionTimeZone", "forceConnectionTimeZoneToSession", "allowPublicKeyRetrieval",
                "sslMode", "connectTimeout", "socketTimeout");

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            Environment environment = context.getEnvironment();
            validateJdbcUrl(environment.getProperty("spring.datasource.url"));
            // These alternate connection sources would bypass the URL that was just checked.
            for (String override : List.of("spring.datasource.hikari.jdbc-url", "spring.datasource.jndi-name",
                    "spring.flyway.url", "spring.flyway.schemas", "spring.flyway.default-schema")) {
                if (hasValue(environment.getProperty(override))) {
                    throw new IllegalStateException("Integration tests do not support alternate datasource or Flyway locations");
                }
            }
            if (!Integer.valueOf(1).equals(environment.getProperty("spring.data.redis.database", Integer.class))
                    || !"campuslife:test:".equals(environment.getProperty("app.redis-prefix"))
                    || hasValue(environment.getProperty("spring.data.redis.url"))) {
                throw new IllegalStateException("Integration tests require Redis database 1, campuslife:test: prefix, and no Redis URL override");
            }
        }

        private static void validateJdbcUrl(String jdbcUrl) {
            try {
                if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql://")) {
                    throw invalidJdbcUrl();
                }
                URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
                if (!"mysql".equals(uri.getScheme()) || uri.getHost() == null
                        || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                        || !"/campuslife_test".equals(uri.getRawPath())
                        || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
                    throw invalidJdbcUrl();
                }
                // Only current driver options are supported; reject database/endpoint override options.
                if (uri.getRawQuery() != null) {
                    for (String option : uri.getRawQuery().split("&", -1)) {
                        int separator = option.indexOf('=');
                        if (separator < 1 || !JDBC_OPTIONS.contains(URLDecoder.decode(
                                option.substring(0, separator), StandardCharsets.UTF_8))) {
                            throw invalidJdbcUrl();
                        }
                    }
                }
            } catch (URISyntaxException | IllegalArgumentException exception) {
                // Do not attach the parser exception: its message can contain the raw connection URL.
                throw invalidJdbcUrl();
            }
        }

        private static boolean hasValue(String value) {
            return value != null && !value.isBlank();
        }

        private static IllegalStateException invalidJdbcUrl() {
            return new IllegalStateException("Integration tests require a single-host jdbc:mysql URL for exactly campuslife_test");
        }
    }
}
