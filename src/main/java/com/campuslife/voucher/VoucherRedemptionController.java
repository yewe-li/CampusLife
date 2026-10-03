package com.campuslife.voucher;

import com.campuslife.auth.AuthService;
import com.campuslife.auth.UserPrincipal;
import com.campuslife.common.ApiResponse;
import com.campuslife.voucher.VoucherModels.Redemption;
import com.campuslife.voucher.VoucherModels.RedemptionRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VoucherRedemptionController {
    private final VoucherRedemptionService redemptions;
    private final AuthService auth;

    public VoucherRedemptionController(VoucherRedemptionService redemptions, AuthService auth) {
        this.redemptions = redemptions;
        this.auth = auth;
    }

    @PostMapping("/api/merchant/vouchers/redemptions")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Redemption> redeem(@RequestBody RedemptionRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserPrincipal merchant = auth.requireUser(authorization);
        return ApiResponse.ok(redemptions.redeem(request, merchant));
    }
}
