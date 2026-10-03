package com.campuslife.voucher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campuslife.common.BusinessException;
import com.campuslife.voucher.VoucherModels.Page;
import com.campuslife.voucher.VoucherModels.MyVoucher;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class VoucherServiceTest {
    @Mock VoucherMapper mapper;
    @Mock VoucherClaimTransaction transaction;
    private VoucherService service;

    @BeforeEach
    void setUp() {
        service = new VoucherService(mapper, transaction, Clock.systemUTC());
    }

    @Test
    void mapsDuplicateOnlyWhenThisUserHasAClaimAfterTransactionFailure() {
        when(transaction.claim(3, 8)).thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.countClaim(3, 8)).thenReturn(1);

        BusinessException result = assertThrows(BusinessException.class, () -> service.claim(3, 8));

        assertEquals("ALREADY_CLAIMED", result.getCode());
        assertEquals(409, result.getStatus().value());
    }

    @Test
    void doesNotCallRandomCodeCollisionADuplicateClaim() {
        DuplicateKeyException collision = new DuplicateKeyException("redeem code collision");
        when(transaction.claim(3, 8)).thenThrow(collision);
        when(mapper.countClaim(3, 8)).thenReturn(0);

        assertSame(collision, assertThrows(DuplicateKeyException.class, () -> service.claim(3, 8)));
    }

    @Test
    void scopesBothPageAndCountToAuthenticatedUserAndUsesLongOffset() {
        long userId = 17;
        long offset = ((long) Integer.MAX_VALUE - 1) * 100;
        when(mapper.countMyVouchers(userId)).thenReturn(1L);
        when(mapper.findMyVouchers(userId, offset, 100)).thenReturn(List.of());

        Page<MyVoucher> result = service.listMine(userId, Integer.MAX_VALUE, 100);

        assertEquals(1, result.total());
        assertEquals(Integer.MAX_VALUE, result.page());
        verify(mapper).countMyVouchers(userId);
        verify(mapper).findMyVouchers(userId, offset, 100);
    }

    @Test
    void rejectsUnboundedPagingBeforeQueryingDatabase() {
        assertThrows(BusinessException.class, () -> service.listMine(3, 1, 101));
        assertThrows(BusinessException.class, () -> service.listMine(3, 0, 10));

        verifyNoInteractions(mapper, transaction);
    }
}
