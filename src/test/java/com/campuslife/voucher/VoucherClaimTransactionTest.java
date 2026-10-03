package com.campuslife.voucher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campuslife.common.BusinessException;
import com.campuslife.voucher.VoucherModels.Claim;
import com.campuslife.voucher.VoucherModels.Offer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class VoucherClaimTransactionTest {
    private static final LocalDateTime START = LocalDateTime.parse("2026-09-30T12:00:00");
    private static final LocalDateTime END = START.plusHours(1);
    private static final LocalDateTime USE_END = START.plusDays(30);

    @Mock VoucherMapper mapper;

    @Test
    void acceptsStartInstantUsingUtcEvenWhenClockHasAnotherZoneAndSnapshotsExpiry() {
        when(mapper.findOffer(8)).thenReturn(offer(1));
        when(mapper.decrementStock(8)).thenReturn(1);
        when(mapper.insertClaim(eq(3L), any())).thenReturn(1);

        Claim result = transactionAt("2026-09-30T12:00:00Z").claim(3, 8);

        assertEquals(START, result.createdAt());
        assertEquals(USE_END, result.expiresAt());
        assertEquals("ISSUED", result.status());
        assertTrue(result.id().matches("[0-9a-f]{32}"));
        assertTrue(result.redeemCode().matches("[0-9a-f]{32}"));
        assertNotEquals(result.id(), result.redeemCode());
        verify(mapper).insertClaim(3, result);
    }

    @Test
    void rejectsEndInstantBeforeChangingInventory() {
        when(mapper.findOffer(8)).thenReturn(offer(1));

        BusinessException result = assertThrows(BusinessException.class,
                () -> transactionAt("2026-09-30T13:00:00Z").claim(3, 8));

        assertEquals("VOUCHER_NOT_ACTIVE", result.getCode());
        verify(mapper, never()).decrementStock(anyLong());
    }

    @Test
    void rejectsBeforeStartBeforeChangingInventory() {
        when(mapper.findOffer(8)).thenReturn(offer(1));

        BusinessException result = assertThrows(BusinessException.class,
                () -> transactionAt("2026-09-30T11:59:59.999999Z").claim(3, 8));

        assertEquals("VOUCHER_NOT_ACTIVE", result.getCode());
        verify(mapper, never()).decrementStock(anyLong());
    }

    @Test
    void rejectsOfflineShopEvenDuringActiveOffer() {
        when(mapper.findOffer(8)).thenReturn(offer(0));

        BusinessException result = assertThrows(BusinessException.class,
                () -> transactionAt("2026-09-30T12:00:00Z").claim(3, 8));

        assertEquals("SHOP_NOT_FOUND", result.getCode());
        verify(mapper, never()).decrementStock(anyLong());
    }

    @Test
    void recognizesSameUserWhoWonTheLastCouponWhileThisRequestWaited() {
        when(mapper.findOffer(8)).thenReturn(offer(1));
        when(mapper.countClaim(3, 8)).thenReturn(0, 1);
        when(mapper.decrementStock(8)).thenReturn(0);

        BusinessException result = assertThrows(BusinessException.class,
                () -> transactionAt("2026-09-30T12:00:00Z").claim(3, 8));

        assertEquals("ALREADY_CLAIMED", result.getCode());
        verify(mapper, never()).insertClaim(anyLong(), any());
    }

    @Test
    void reportsSoldOutAndNeverInsertsWithoutAStockDecrement() {
        when(mapper.findOffer(8)).thenReturn(offer(1));
        when(mapper.decrementStock(8)).thenReturn(0);

        BusinessException result = assertThrows(BusinessException.class,
                () -> transactionAt("2026-09-30T12:00:00Z").claim(3, 8));

        assertEquals("SOLD_OUT", result.getCode());
        verify(mapper, never()).insertClaim(anyLong(), any());
    }

    @Test
    void letsInsertFailureEscapeSoSpringCanRollbackStock() {
        DuplicateKeyException failure = new DuplicateKeyException("constraint violation");
        when(mapper.findOffer(8)).thenReturn(offer(1));
        when(mapper.decrementStock(8)).thenReturn(1);
        when(mapper.insertClaim(eq(3L), any())).thenThrow(failure);

        DuplicateKeyException result = assertThrows(DuplicateKeyException.class,
                () -> transactionAt("2026-09-30T12:00:00Z").claim(3, 8));

        assertSame(failure, result);
    }

    private VoucherClaimTransaction transactionAt(String instant) {
        return new VoucherClaimTransaction(mapper,
                Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Shanghai")));
    }

    private static Offer offer(int shopStatus) {
        return new Offer(8, 2, "咖啡优惠", 500, 1000, START, END, USE_END, 1, shopStatus);
    }
}
