package com.campuslife.voucher;

import com.campuslife.common.BusinessException;
import com.campuslife.voucher.VoucherModels.Claim;
import com.campuslife.voucher.VoucherModels.Offer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** This separate bean ensures a failed insert is rolled back before the caller maps its error. */
@Service
public class VoucherClaimTransaction {
    private final VoucherMapper mapper;
    private final Clock clock;

    public VoucherClaimTransaction(VoucherMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Claim claim(long userId, long voucherId) {
        Offer offer = mapper.findOffer(voucherId);
        if (offer == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "VOUCHER_NOT_FOUND", "优惠券不存在");
        }
        if (offer.shopStatus() != 1) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SHOP_NOT_FOUND", "店铺不存在或已下线");
        }

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        if (offer.status() != 1 || now.isBefore(offer.claimStart()) || !now.isBefore(offer.claimEnd())
                || !now.isBefore(offer.useEnd())) {
            throw new BusinessException(HttpStatus.CONFLICT, "VOUCHER_NOT_ACTIVE", "优惠券不在可领取时间内");
        }
        if (mapper.countClaim(userId, voucherId) > 0) {
            throw alreadyClaimed();
        }
        if (mapper.decrementStock(voucherId) != 1) {
            // The stock update may have waited for this user's concurrent successful claim.
            // READ_COMMITTED makes this query see that committed record, including the last coupon.
            if (mapper.countClaim(userId, voucherId) > 0) {
                throw alreadyClaimed();
            }
            throw new BusinessException(HttpStatus.CONFLICT, "SOLD_OUT", "优惠券已领完");
        }

        Claim claim = new Claim(randomId(), voucherId, randomId(), offer.useEnd(), "ISSUED", now);
        if (mapper.insertClaim(userId, claim) != 1) {
            throw new IllegalStateException("Voucher claim insert did not affect one row");
        }
        return claim;
    }

    static BusinessException alreadyClaimed() {
        return new BusinessException(HttpStatus.CONFLICT, "ALREADY_CLAIMED", "你已领取过该优惠券");
    }

    private static String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
