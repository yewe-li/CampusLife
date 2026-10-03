package com.campuslife.voucher;

import com.campuslife.auth.AuthService;
import com.campuslife.auth.UserPrincipal;
import com.campuslife.common.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.campuslife.voucher.VoucherModels.Claim;
import com.campuslife.voucher.VoucherModels.MyVoucher;
import com.campuslife.voucher.VoucherModels.Page;
import com.campuslife.voucher.VoucherModels.PublicVoucher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VoucherController {
    private final VoucherService vouchers;
    private final AuthService auth;

    public VoucherController(VoucherService vouchers, AuthService auth) {
        this.vouchers = vouchers;
        this.auth = auth;
    }

    @GetMapping("/api/shops/{shopId}/vouchers")
    public ApiResponse<Page<PublicVoucher>> listForShop(@PathVariable long shopId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(vouchers.listForShop(shopId, page, size));
    }

    @PostMapping("/api/vouchers/{id}/claims")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Claim> claim(@PathVariable long id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserPrincipal user = auth.requireUser(authorization);
        return ApiResponse.ok(vouchers.claim(user.id(), id));
    }

    @GetMapping("/api/me/vouchers")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Page<MyVoucher>> mine(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
        UserPrincipal user = auth.requireUser(authorization);
        return ApiResponse.ok(vouchers.listMine(user.id(), page, size));
    }
}
