package com.campuslife.support;

import com.campuslife.CampusLifeApplication;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Test-classpath-only launcher. It never resets the development database. */
public final class BrowserTestApplication {
    private BrowserTestApplication() {}

    public static void main(String[] ignored) {
        int port = Integer.parseInt(System.getenv().getOrDefault("CAMPUSLIFE_BROWSER_PORT", "18080"));
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Browser test port must be between 1024 and 65535");
        }
        SpringApplication app = new SpringApplication(CampusLifeApplication.class);
        // Runs before Flyway: same strict database/Redis guard as the integration tests.
        app.addInitializers(new IntegrationSupport.TestInfrastructureGuard());
        ConfigurableApplicationContext context = app.run(
                "--spring.profiles.active=test", "--server.address=127.0.0.1", "--server.port=" + port,
                "--management.server.address=127.0.0.1", "--management.server.port=0");
        try {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            if (!"campuslife_test".equals(jdbc.queryForObject("SELECT DATABASE()", String.class))) {
                throw new IllegalStateException("Browser tests require the dedicated campuslife_test database");
            }
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                ScriptUtils.executeSqlScript(connection,
                        new EncodedResource(new ClassPathResource("reset.sql"), "UTF-8"));
                return null;
            });
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
            List<String> keys = new ArrayList<>();
            try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                    .match("campuslife:test:*").count(100).build())) {
                cursor.forEachRemaining(keys::add);
            }
            if (!keys.isEmpty()) redis.delete(keys);
            System.out.println("CAMPUSLIFE_BROWSER_FIXTURE_READY");
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }
}
