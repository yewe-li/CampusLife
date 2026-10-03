package com.campuslife.shop;

import com.campuslife.auth.AuthService;
import com.campuslife.auth.UserPrincipal;
import com.campuslife.common.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shops")
public class ShopController {
    private final ShopService shops;
    private final AuthService auth;

    public ShopController(ShopService shops, AuthService auth) {
        this.shops = shops;
        this.auth = auth;
    }

    @GetMapping
    public ApiResponse<ShopPage> list(
            @RequestParam(required = false) String campus,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer maxPrice,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(shops.list(campus, category, maxPrice, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<ShopView> detail(@PathVariable long id) {
        return ApiResponse.ok(shops.detail(id));
    }

    @PatchMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<ShopView> update(@PathVariable long id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ShopUpdateRequest request) {
        UserPrincipal user = auth.requireUser(authorization);
        return ApiResponse.ok(shops.update(id, request, user));
    }
}
