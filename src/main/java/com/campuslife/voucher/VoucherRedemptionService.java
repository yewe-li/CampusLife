package com.campuslife.voucher;

import com.campuslife.auth.UserPrincipal;
import com.campuslife.common.BusinessException;
import com.campuslife.voucher.VoucherModels.Redemption;
import com.campuslife.voucher.VoucherModels.RedemptionRequest;
import com.campuslife.voucher.VoucherModels.RedemptionRow;
import com.campuslife.voucher.VoucherModels.RedemptionShop;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Records a merchant's confirmation of offline use, not payment or spend verification. */
@Service
public class VoucherRedemptionService {
    private static final Pattern REDEEM_CODE = Pattern.compile("[0-9a-f]{32}");
    private final VoucherMapper mapper;
    private final Clock clock;

    public VoucherRedemptionService(VoucherMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Redemption redeem(RedemptionRequest request, UserPrincipal merchant) {
        if (merchant == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录");
        }
        if (!"MERCHANT".equals(merchant.role())) {
            throw forbidden();
        }
        if (request == null || request.redeemCode() == null
                || !REDEEM_CODE.matcher(request.redeemCode()).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "核销码须为 32 位小写十六进制字符");
        }

        // Lock only this issued record. Competing requests see the committed state after waiting.
        RedemptionRow order = mapper.lockForRedemption(request.redeemCode());
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "CLAIM_NOT_FOUND", "领取记录不存在");
        }
        RedemptionShop shop = mapper.findRedemptionShop(order.voucherId());
        if (shop == null || shop.merchantId() != merchant.id()) {
            // Check ownership before returning status or expiry information.
            throw forbidden();
        }
        if ("REDEEMED".equals(order.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "ALREADY_REDEEMED", "该优惠券已核销，请勿重复操作");
        }
        // Read time after acquiring the row lock, so time spent waiting cannot extend validity.
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        if (!now.isBefore(order.expiresAt())) {
            throw new BusinessException(HttpStatus.CONFLICT, "VOUCHER_EXPIRED", "该优惠券已过期，不能核销");
        }
        // Shop/offer visibility does not cancel previously issued, unexpired rights.
        if (mapper.redeem(order.id(), merchant.id(), now) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "REDEMPTION_CONFLICT", "优惠券状态已变化，请刷新后重试");
        }
        return new Redemption(order.id(), order.voucherId(), shop.shopId(), shop.voucherTitle(),
                shop.shopName(), "REDEEMED", now);
    }

    private static BusinessException forbidden() {
        return new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅本店商户可以核销该优惠券");
    }
}
