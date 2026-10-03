package com.campuslife.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** No Spring Boot startup, datasource bean, Redis client, or network connection is created here. */
class TestInfrastructureGuardTest {
    private static final String TEST_URL = "jdbc:mysql://127.0.0.1:3306/campuslife_test";
    private final IntegrationSupport.TestInfrastructureGuard guard =
            new IntegrationSupport.TestInfrastructureGuard();

    @ParameterizedTest
    @ValueSource(strings = {
            TEST_URL,
            "jdbc:mysql://localhost/campuslife_test?useUnicode=true&characterEncoding=UTF-8"
                    + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                    + "&allowPublicKeyRetrieval=true&sslMode=DISABLED"
    })
    void acceptsDedicatedDatabaseAndRedisNamespaceWithoutRefreshingContext(String url) {
        try (GenericApplicationContext context = context(validEnvironment()
                .withProperty("spring.datasource.url", url))) {
            assertDoesNotThrow(() -> guard.initialize(context));
            assertFalse(context.isActive());
        }
    }

    @ParameterizedTest(name = "rejects unsafe JDBC configuration {index}")
    @ValueSource(strings = {
            "jdbc:mysql://127.0.0.1:3306/campuslife",
            "jdbc:mysql://127.0.0.1:3306/unrelated_test",
            "jdbc:mysql://127.0.0.1:3306,localhost:3306/campuslife_test",
            "jdbc:mysql:replication://127.0.0.1:3306,localhost:3306/campuslife_test",
            TEST_URL + "?DBNAME=campuslife",
            TEST_URL + "?%44BNAME=campuslife",
            "jdbc:mysql://test-user:test-only-placeholder@localhost:3306/campuslife_test",
            TEST_URL + "#campuslife",
            "jdbc:mysql://127.0.0.1:3306/campuslife%5Ftest",
            "jdbc:mysql://127.0.0.1:65536/campuslife_test",
            "jdbc:mysql://127.0.0.1:3306/campuslife_test?sslMode=%"
    })
    void rejectsWrongDatabaseOrUnsupportedUrlWithoutLeakingRawUrl(String url) {
        try (GenericApplicationContext context = context(validEnvironment()
                .withProperty("spring.datasource.url", url))) {
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> guard.initialize(context));
            assertFalse(rejected.getMessage().contains(url));
            assertNull(rejected.getCause(), "Parser exceptions can contain the unredacted connection URL");
            assertFalse(context.isActive());
        }
    }

    @ParameterizedTest(name = "rejects connection override {0}")
    @MethodSource("unsafeOverrides")
    void rejectsRedisOrAlternateDatasourceConfiguration(String property, String value) {
        try (GenericApplicationContext context = context(validEnvironment().withProperty(property, value))) {
            assertThrows(IllegalStateException.class, () -> guard.initialize(context));
            assertFalse(context.isActive());
        }
    }

    @Test
    void refusesMissingDatasourceUrlBeforeAnyContextStartup() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.data.redis.database", "1")
                .withProperty("app.redis-prefix", "campuslife:test:");
        try (GenericApplicationContext context = context(environment)) {
            assertThrows(IllegalStateException.class, () -> guard.initialize(context));
            assertFalse(context.isActive());
        }
    }

    private static Stream<Arguments> unsafeOverrides() {
        return Stream.of(
                Arguments.of("spring.data.redis.database", "0"),
                Arguments.of("app.redis-prefix", "campuslife:dev:"),
                Arguments.of("spring.data.redis.url", "redis://127.0.0.1:6379/1"),
                Arguments.of("spring.flyway.url", "jdbc:mysql://127.0.0.1:3306/campuslife"),
                Arguments.of("spring.flyway.schemas", "campuslife"),
                Arguments.of("spring.flyway.default-schema", "campuslife"),
                Arguments.of("spring.datasource.hikari.jdbc-url", "jdbc:mysql://127.0.0.1:3306/campuslife"),
                Arguments.of("spring.datasource.jndi-name", "java:comp/env/jdbc/development"));
    }

    private static MockEnvironment validEnvironment() {
        return new MockEnvironment().withProperty("spring.datasource.url", TEST_URL)
                .withProperty("spring.data.redis.database", "1")
                .withProperty("app.redis-prefix", "campuslife:test:");
    }

    private static GenericApplicationContext context(MockEnvironment environment) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment);
        return context;
    }
}
