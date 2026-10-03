package com.campuslife.voucher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campuslife.auth.UserPrincipal;
import com.campuslife.common.BusinessException;
import com.campuslife.voucher.VoucherModels.Claim;
import com.campuslife.voucher.VoucherModels.MyVoucher;
import com.campuslife.voucher.VoucherModels.RedemptionRequest;
import com.campuslife.voucher.VoucherModels.RedemptionRow;
import com.campuslife.voucher.VoucherModels.RedemptionShop;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoucherRedemptionServiceTest {
    private static final String CODE = "0123456789abcdef0123456789abcdef";
    private static final String ORDER_ID = "abcdef0123456789abcdef0123456789";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 4, 0);
    private static final UserPrincipal MERCHANT = new UserPrincipal(101, "商户", "MERCHANT");
    @Mock VoucherMapper mapper;
    @Mock Clock clock;
    private VoucherRedemptionService service;

    @BeforeEach
    void setUp() {
        service = new VoucherRedemptionService(mapper, clock);
    }

    @Test
    void rejectsUnauthenticatedOrNonMerchantBeforeLookingUpCode() {
        error("AUTH_REQUIRED", 401, () -> service.redeem(new RedemptionRequest(CODE), null));
        error("FORBIDDEN", 403, () -> service.redeem(new RedemptionRequest(CODE),
                new UserPrincipal(1, "用户", "USER")));
        verifyNoInteractions(mapper, clock);
    }

    @Test
    void validatesExactExistingLowercaseHexFormatWithoutEchoingInput() {
        for (String invalid : new String[] {null, "", CODE.substring(1), CODE + "0", CODE.toUpperCase(),
                " " + CODE, CODE + " ", "g".repeat(32), "\n" + CODE}) {
            error("INVALID_ARGUMENT", 400, () -> service.redeem(new RedemptionRequest(invalid), MERCHANT));
        }
        error("INVALID_ARGUMENT", 400, () -> service.redeem(null, MERCHANT));
        verifyNoInteractions(mapper, clock);
    }

    @Test
    void rejectsMissingClaim() {
        error("CLAIM_NOT_FOUND", 404, () -> service.redeem(new RedemptionRequest(CODE), MERCHANT));
        verifyNoInteractions(clock);
    }

    @Test
    void checksOwnershipBeforeRevealingWhetherClaimWasRedeemed() {
        when(mapper.lockForRedemption(CODE)).thenReturn(row("REDEEMED", NOW.plusDays(1)));
        when(mapper.findRedemptionShop(1)).thenReturn(new RedemptionShop(1, 102, "优惠", "他店"));
        error("FORBIDDEN", 403, () -> service.redeem(new RedemptionRequest(CODE), MERCHANT));
        verify(mapper, never()).redeem(anyString(), anyLong(), any());
        verifyNoInteractions(clock);
    }

    @Test
    void repeatedRequestDoesNotOverwriteOriginalRedemption() {
        ownedOrder("REDEEMED", NOW.plusDays(1));
        error("ALREADY_REDEEMED", 409, () -> service.redeem(new RedemptionRequest(CODE), MERCHANT));
        verify(mapper, never()).redeem(anyString(), anyLong(), any());
        verifyNoInteractions(clock);
    }

    @Test
    void checksCurrentClockAfterWaitingForRowLockAndRejectsExactExpiry() {
        ownedOrder("ISSUED", NOW);
        when(clock.instant()).thenReturn(NOW.toInstant(ZoneOffset.UTC));
        error("VOUCHER_EXPIRED", 409, () -> service.redeem(new RedemptionRequest(CODE), MERCHANT));
        var order = org.mockito.Mockito.inOrder(mapper, clock);
        order.verify(mapper).lockForRedemption(CODE);
        order.verify(mapper).findRedemptionShop(1);
        order.verify(clock).instant();
        verify(mapper, never()).redeem(anyString(), anyLong(), any());
    }

    @Test
    void returnsMinimalReceiptUsingServerTimeAndMerchantIdentity() {
        ownedOrder("ISSUED", NOW.plusDays(1));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-03T04:00:00.000000999Z"));
        when(mapper.redeem(ORDER_ID, 101, NOW)).thenReturn(1);
        var result = service.redeem(new RedemptionRequest(CODE), MERCHANT);
        assertEquals(ORDER_ID, result.id());
        assertEquals("REDEEMED", result.status());
        assertEquals(1, result.shopId());
        assertEquals(NOW, result.redeemedAt());
        assertFalse(result.toString().contains(CODE));
    }

    @Test
    void failsClosedIfConditionalUpdateNoLongerMatches() {
        ownedOrder("ISSUED", NOW.plusDays(1));
        when(clock.instant()).thenReturn(NOW.toInstant(ZoneOffset.UTC));
        error("REDEMPTION_CONFLICT", 409, () -> service.redeem(new RedemptionRequest(CODE), MERCHANT));
    }

    @Test
    void sensitiveVoucherModelsDoNotPrintRedeemCode() {
        assertFalse(new RedemptionRequest(CODE).toString().contains(CODE));
        assertFalse(new Claim(ORDER_ID, 1, CODE, NOW.plusDays(1), "ISSUED", NOW).toString().contains(CODE));
        assertFalse(new MyVoucher(ORDER_ID, 1, CODE, NOW.plusDays(1), "ISSUED", NOW,
                "优惠", "本店", null).toString().contains(CODE));
    }

    private void ownedOrder(String status, LocalDateTime expiresAt) {
        when(mapper.lockForRedemption(CODE)).thenReturn(row(status, expiresAt));
        when(mapper.findRedemptionShop(1)).thenReturn(new RedemptionShop(1, 101, "优惠", "本店"));
    }

    private RedemptionRow row(String status, LocalDateTime expiresAt) {
        return new RedemptionRow(ORDER_ID, 1, expiresAt, status);
    }

    private void error(String code, int status, org.junit.jupiter.api.function.Executable action) {
        BusinessException exception = assertThrows(BusinessException.class, action);
        assertEquals(code, exception.getCode());
        assertEquals(status, exception.getStatus().value());
        assertFalse(exception.getMessage().contains(CODE));
    }
}
