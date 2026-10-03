package com.campuslife.voucher;

import com.campuslife.common.BusinessException;
import com.campuslife.voucher.VoucherModels.Claim;
import com.campuslife.voucher.VoucherModels.MyVoucher;
import com.campuslife.voucher.VoucherModels.Page;
import com.campuslife.voucher.VoucherModels.PublicVoucher;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoucherService {
    private final VoucherMapper mapper;
    private final VoucherClaimTransaction claimTransaction;
    private final Clock clock;

    public VoucherService(VoucherMapper mapper, VoucherClaimTransaction claimTransaction, Clock clock) {
        this.mapper = mapper;
        this.claimTransaction = claimTransaction;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<PublicVoucher> listForShop(long shopId, int page, int size) {
        validateId(shopId);
        long offset = offset(page, size);
        if (mapper.countOnlineShop(shopId) == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SHOP_NOT_FOUND", "店铺不存在或已下线");
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        long total = mapper.countPublicVouchers(shopId, now);
        return new Page<>(mapper.findPublicVouchers(shopId, now, offset, size), total, page, size);
    }

    // Intentionally outside a transaction: the proxied claim transaction must finish rollback first.
    public Claim claim(long userId, long voucherId) {
        validateId(voucherId);
        try {
            return claimTransaction.claim(userId, voucherId);
        } catch (DuplicateKeyException exception) {
            if (mapper.countClaim(userId, voucherId) > 0) {
                throw VoucherClaimTransaction.alreadyClaimed();
            }
            // A collision on the random order id or redeem code is not a duplicate user claim.
            throw exception;
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<MyVoucher> listMine(long userId, int page, int size) {
        long offset = offset(page, size);
        long total = mapper.countMyVouchers(userId);
        return new Page<>(mapper.findMyVouchers(userId, offset, size), total, page, size);
    }

    private static void validateId(long id) {
        if (id <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "编号必须大于零");
        }
    }

    private static long offset(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "page必须大于零，size必须介于1和100之间");
        }
        return ((long) page - 1) * size;
    }
}
