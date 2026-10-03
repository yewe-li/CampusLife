package com.campuslife.auth;

import com.campuslife.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AuthService {
    private static final Pattern PHONE = Pattern.compile("1[0-9]{10}");
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final int MAX_CODE_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> DEMO_PROFILES = Set.of("dev", "test");

    // Both keys use the same hash tag so the operation remains atomic on Redis Cluster.
    private static final DefaultRedisScript<Long> ISSUE_CODE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return 0
            end
            redis.call('HSET', KEYS[1], 'digest', ARGV[1], 'attempts', 0)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            if tonumber(ARGV[3]) > 0 then
                redis.call('SET', KEYS[2], '1', 'EX', tonumber(ARGV[3]))
            end
            return 1
            """, Long.class);

    // Compare and delete in one operation: concurrent requests cannot consume one OTP twice.
    // Wrong attempts keep the original expiry; the fifth wrong attempt destroys the OTP.
    private static final DefaultRedisScript<Long> CONSUME_CODE = new DefaultRedisScript<>("""
            local expected = redis.call('HGET', KEYS[1], 'digest')
            if not expected then
                return 0
            end
            if expected == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 1
            end
            local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if attempts >= tonumber(ARGV[2]) then
                redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final UserMapper users;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean devCodeEnabled;
    private final String prefix;
    private final long codeTtlSeconds;
    private final long cooldownSeconds;
    private final long sessionTtlSeconds;

    public AuthService(UserMapper users, StringRedisTemplate redis, ObjectMapper objectMapper,
                       Environment environment,
                       @Value("${app.auth.dev-code-enabled:false}") boolean devCodeEnabled,
                       @Value("${app.redis-prefix:campuslife:}") String prefix,
                       @Value("${app.auth.code-ttl-seconds:120}") long codeTtlSeconds,
                       @Value("${app.auth.code-cooldown-seconds:60}") long cooldownSeconds,
                       @Value("${app.auth.session-ttl-seconds:7200}") long sessionTtlSeconds) {
        this.users = users;
        this.redis = redis;
        this.objectMapper = objectMapper;
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            profiles = environment.getDefaultProfiles();
        }
        this.devCodeEnabled = devCodeEnabled && profiles.length > 0
                && Arrays.stream(profiles).allMatch(DEMO_PROFILES::contains);
        if (codeTtlSeconds <= 0 || cooldownSeconds < 0 || sessionTtlSeconds <= 0) {
            throw new IllegalArgumentException("Auth TTLs must be positive and cooldown nonnegative");
        }
        this.prefix = prefix;
        this.codeTtlSeconds = codeTtlSeconds;
        this.cooldownSeconds = cooldownSeconds;
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    public CodeResponse issueCode(String phone) {
        validatePhone(phone);
        if (!devCodeEnabled) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_PROVIDER_NOT_CONFIGURED",
                    "当前环境未接入短信服务；仅 dev/test 环境可启用演示验证码");
        }
        requireSeedAccount(phone);
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        try {
            Long issued = redis.execute(ISSUE_CODE, List.of(codeKey(phone), cooldownKey(phone)),
                    codeDigest(phone, code), Long.toString(codeTtlSeconds), Long.toString(cooldownSeconds));
            if (issued == null) {
                throw unavailable();
            }
            if (issued != 1L) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "CODE_RATE_LIMITED",
                        "验证码请求过于频繁，请稍后重试");
            }
            return new CodeResponse(code, codeTtlSeconds);
        } catch (DataAccessException e) {
            throw unavailable();
        }
    }

    public LoginResponse login(String phone, String code) {
        validatePhone(phone);
        if (code == null || !CODE.matcher(code).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "验证码必须是 6 位数字");
        }
        UserPrincipal user = requireSeedAccount(phone);
        try {
            Long consumed = redis.execute(CONSUME_CODE, List.of(codeKey(phone)),
                    codeDigest(phone, code), Integer.toString(MAX_CODE_ATTEMPTS));
            if (consumed == null) {
                throw unavailable();
            }
            if (consumed != 1L) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CODE", "验证码错误、已失效或已被使用");
            }
            String sessionJson = objectMapper.writeValueAsString(user);
            // SET NX avoids replacing an existing session even in the extremely unlikely event of a collision.
            for (int attempt = 0; attempt < 3; attempt++) {
                byte[] bytes = new byte[32];
                RANDOM.nextBytes(bytes);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                Boolean stored = redis.opsForValue().setIfAbsent(sessionKey(token), sessionJson,
                        Duration.ofSeconds(sessionTtlSeconds));
                if (stored == null) {
                    throw unavailable();
                }
                if (stored) {
                    return new LoginResponse(token, sessionTtlSeconds, user);
                }
            }
            throw unavailable();
        } catch (DataAccessException | JsonProcessingException e) {
            // No token, phone, OTP, Redis command, or serialization input is included in logs/errors.
            throw unavailable();
        }
    }

    public UserPrincipal requireUser(String authorization) {
        String token = requireToken(authorization);
        try {
            String json = redis.opsForValue().get(sessionKey(token));
            if (json == null) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_SESSION", "登录已过期，请重新登录");
            }
            UserPrincipal principal = objectMapper.readValue(json, UserPrincipal.class);
            if (principal == null || principal.id() <= 0 || principal.nickname() == null
                    || !("USER".equals(principal.role()) || "MERCHANT".equals(principal.role()))) {
                throw unavailable();
            }
            // GET deliberately does not refresh expiry: the session has a fixed lifetime.
            return principal;
        } catch (DataAccessException | JsonProcessingException e) {
            throw unavailable();
        }
    }

    public void logout(String authorization) {
        String token = requireToken(authorization);
        try {
            // Idempotent logout: deleting an already expired session is also successful.
            redis.delete(sessionKey(token));
        } catch (DataAccessException e) {
            throw unavailable();
        }
    }

    private UserPrincipal requireSeedAccount(String phone) {
        UserPrincipal user = users.findByPhone(phone);
        if (user == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "当前仅支持预设演示账号，不提供注册");
        }
        return user;
    }

    private static void validatePhone(String phone) {
        if (phone == null || !PHONE.matcher(phone).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "手机号必须是以 1 开头的 11 位数字");
        }
    }

    private static String requireToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录并提供 Bearer token");
        }
        String token = authorization.substring(7);
        if (!TOKEN.matcher(token).matches()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_SESSION", "登录凭据格式无效");
        }
        return token;
    }

    private String codeKey(String phone) {
        return prefix + "auth:code:{" + phone + "}";
    }

    private String cooldownKey(String phone) {
        return prefix + "auth:cooldown:{" + phone + "}";
    }

    private String sessionKey(String token) {
        return prefix + "auth:session:" + sha256(token);
    }

    private static String codeDigest(String phone, String code) {
        return sha256(phone + ":" + code);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM must support SHA-256");
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE", "登录服务暂不可用，请稍后重试");
    }

    public record CodeResponse(String code, long expiresInSeconds) {
        @Override
        public String toString() {
            return "CodeResponse[code=REDACTED, expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    public record LoginResponse(String token, long expiresInSeconds, UserPrincipal user) {
        @Override
        public String toString() {
            return "LoginResponse[token=REDACTED, expiresInSeconds=" + expiresInSeconds + ", user=" + user + "]";
        }
    }
}
