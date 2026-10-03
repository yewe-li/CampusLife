package com.campuslife.auth;

import com.campuslife.common.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/code")
    public ApiResponse<AuthService.CodeResponse> code(@Valid @RequestBody CodeRequest request) {
        return ApiResponse.ok(auth.issueCode(request.phone()));
    }

    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(auth.login(request.phone(), request.code()));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<UserPrincipal> me(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ApiResponse.ok(auth.requireUser(authorization));
    }

    @PostMapping("/logout")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        auth.logout(authorization);
        return ApiResponse.ok(null);
    }

    public record CodeRequest(
            @NotBlank @Pattern(regexp = "1[0-9]{10}", message = "手机号必须是以 1 开头的 11 位数字") String phone) {
        @Override
        public String toString() {
            return "CodeRequest[phone=REDACTED]";
        }
    }

    public record LoginRequest(
            @NotBlank @Pattern(regexp = "1[0-9]{10}", message = "手机号必须是以 1 开头的 11 位数字") String phone,
            @NotBlank @Pattern(regexp = "[0-9]{6}", message = "验证码必须是 6 位数字") String code) {
        @Override
        public String toString() {
            return "LoginRequest[phone=REDACTED, code=REDACTED]";
        }
    }
}
