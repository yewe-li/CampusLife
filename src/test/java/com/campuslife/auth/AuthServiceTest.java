package com.campuslife.auth;

import com.campuslife.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class AuthServiceTest {
    private static final String PHONE = "13800000001";
    private static final String TOKEN = "a".repeat(43);
    private static final UserPrincipal USER = new UserPrincipal(1, "演示用户", "USER");

    @Mock private UserMapper users;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AuthService auth;

    @BeforeEach
    void setUp() {
        auth = service(true, "test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "prod", "dev,prod"})
    void codeEchoCannotBeEnabledOutsideExclusiveDemoProfiles(String profiles) {
        AuthService service = service(true, profiles.split(","));

        assertFailure(() -> service.issueCode(PHONE), HttpStatus.SERVICE_UNAVAILABLE,
                "SMS_PROVIDER_NOT_CONFIGURED");
        verifyNoInteractions(users, redis);
    }

    @Test
    void demoProfileStillRequiresExplicitCodeOptIn() {
        assertFailure(() -> service(false, "dev").issueCode(PHONE), HttpStatus.SERVICE_UNAVAILABLE,
                "SMS_PROVIDER_NOT_CONFIGURED");
        verifyNoInteractions(users, redis);
    }

    @Test
    void explicitlyEnabledDefaultDevProfileCanIssueCode() {
        MockEnvironment environment = new MockEnvironment();
        environment.setDefaultProfiles("dev");
        AuthService service = new AuthService(users, redis, objectMapper, environment, true,
                "test:", 120, 60, 7200);
        when(users.findByPhone(PHONE)).thenReturn(USER);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);

        AuthService.CodeResponse response = service.issueCode(PHONE);

        assertThat(response.code()).matches("[0-9]{6}");
        assertThat(response.expiresInSeconds()).isEqualTo(120);
        assertThat(response.toString()).doesNotContain(response.code());
    }

    @Test
    void unknownAccountCannotReceiveAnOtpOrRegister() {
        when(users.findByPhone(PHONE)).thenReturn(null);

        assertFailure(() -> auth.issueCode(PHONE), HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND");
        verifyNoInteractions(redis);
    }

    @Test
    void consumedOrInvalidOtpCannotCreateSession() {
        when(users.findByPhone(PHONE)).thenReturn(USER);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);

        assertFailure(() -> auth.login(PHONE, "123456"), HttpStatus.UNAUTHORIZED, "INVALID_CODE");
        verify(redis, never()).opsForValue();
    }

    @Test
    void loginStoresOnlyHashedRandomTokenWithFixedTtlAndJsonIdentity() throws Exception {
        when(users.findByPhone(PHONE)).thenReturn(USER);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        AuthService.LoginResponse response = auth.login(PHONE, "123456");

        assertThat(response.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(response.expiresInSeconds()).isEqualTo(7200);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).setIfAbsent(key.capture(), json.capture(), eq(Duration.ofHours(2)));
        assertThat(key.getValue()).matches("test:auth:session:[a-f0-9]{64}");
        assertThat(key.getValue()).doesNotContain(response.token());
        assertThat(objectMapper.readValue(json.getValue(), UserPrincipal.class)).isEqualTo(USER);
        assertThat(response.toString()).doesNotContain(response.token());
    }

    @Test
    void authenticationFailsClosedWhenRedisIsUnavailable() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));

        assertFailure(() -> auth.requireUser("Bearer " + TOKEN), HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_UNAVAILABLE");
        verifyNoInteractions(users);
    }

    @Test
    void corruptSessionCannotBecomeAnAuthenticatedUser() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("{\"id\":1,\"nickname\":\"demo\",\"role\":\"ADMIN\"}");

        assertFailure(() -> auth.requireUser("Bearer " + TOKEN), HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_UNAVAILABLE");
    }

    @Test
    void validSessionReadDoesNotRefreshItsExpiry() throws Exception {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(objectMapper.writeValueAsString(USER));

        assertThat(auth.requireUser("Bearer " + TOKEN)).isEqualTo(USER);
        verify(redis).opsForValue();
        verify(values).get(anyString());
        verifyNoMoreInteractions(redis, values);
    }

    @Test
    void logoutDeletesOnlyThePresentedSessionAndFailsClosedOnRedisError() {
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("offline"));

        assertFailure(() -> auth.logout("Bearer " + TOKEN), HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_UNAVAILABLE");
        verify(redis).delete(anyString());
        verifyNoInteractions(users);
    }

    @Test
    void malformedAuthorizationCannotReachRedis() {
        assertFailure(() -> auth.requireUser(null), HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
        assertFailure(() -> auth.requireUser("Bearer x"), HttpStatus.UNAUTHORIZED, "INVALID_SESSION");
        verifyNoInteractions(redis);
    }

    private AuthService service(boolean codeEnabled, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new AuthService(users, redis, objectMapper, environment, codeEnabled,
                "test:", 120, 60, 7200);
    }

    private static void assertFailure(Runnable action, HttpStatus status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(status);
            assertThat(exception.getCode()).isEqualTo(code);
        });
    }
}
