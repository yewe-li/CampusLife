package com.campuslife.shop;

import com.campuslife.auth.AuthService;
import com.campuslife.auth.UserPrincipal;
import com.campuslife.common.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The merchant identity always comes from the server-owned login session. */
@RestController
@RequestMapping("/api/merchant/shops")
@SecurityRequirement(name = "bearerAuth")
public class MerchantShopController {
    private final ShopService shops;
    private final AuthService auth;

    public MerchantShopController(ShopService shops, AuthService auth) {
        this.shops = shops;
        this.auth = auth;
    }

    @GetMapping
    public ApiResponse<ShopPage> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        UserPrincipal user = auth.requireUser(authorization);
        return ApiResponse.ok(shops.listForMerchant(user, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<ShopView> detail(@PathVariable long id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserPrincipal user = auth.requireUser(authorization);
        return ApiResponse.ok(shops.detailForMerchant(id, user));
    }
}
