package com.campuslife.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campuslife.auth.UserPrincipal;
import com.campuslife.common.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ShopServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T01:00:00Z"), ZoneOffset.UTC);
    private static final UserPrincipal OWNER = new UserPrincipal(101L, "商户", "MERCHANT");
    private static final ShopUpdateRequest UPDATE = new ShopUpdateRequest("新店名", null, null, 2);
    @Mock private ShopMapper mapper;
    @Mock private ShopCache cache;
    private ShopService service;

    @BeforeEach
    void setUp() {
        service = new ShopService(mapper, cache, CLOCK);
    }

    @AfterEach
    void cleanTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void cachedMissingShopDoesNotQueryDatabase() {
        when(cache.lookup(7L)).thenReturn(new ShopCache.Lookup(true, true, null));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.detail(7L));

        assertEquals("SHOP_NOT_FOUND", exception.getCode());
        verifyNoInteractions(mapper);
    }

    @Test
    void merchantQueriesRejectMissingLoginBeforeAccessingData() {
        BusinessException listError = assertThrows(BusinessException.class,
                () -> service.listForMerchant(null, 1, 10));
        BusinessException detailError = assertThrows(BusinessException.class,
                () -> service.detailForMerchant(1L, null));

        assertEquals(401, listError.getStatus().value());
        assertEquals(401, detailError.getStatus().value());
        verifyNoInteractions(mapper, cache);
    }

    @Test
    void merchantQueriesRejectOrdinaryUserBeforeAccessingData() {
        UserPrincipal ordinaryUser = new UserPrincipal(101L, "用户", "USER");
        BusinessException listError = assertThrows(BusinessException.class,
                () -> service.listForMerchant(ordinaryUser, 1, 10));
        BusinessException detailError = assertThrows(BusinessException.class,
                () -> service.detailForMerchant(1L, ordinaryUser));

        assertEquals("FORBIDDEN", listError.getCode());
        assertEquals("FORBIDDEN", detailError.getCode());
        verifyNoInteractions(mapper, cache);
    }

    @Test
    void merchantDetailReadsOfflineShopWithoutConsultingPublicCache() {
        ShopRow offline = new ShopRow(1L, 101L, "咖啡店", "东校区", "咖啡", 1800,
                0, 2, "校园咖啡", "一号楼", LocalDateTime.of(2026, 1, 1, 0, 0));
        when(mapper.findById(1L)).thenReturn(offline);

        ShopView result = service.detailForMerchant(1L, OWNER);

        assertEquals(0, result.status());
        assertEquals(2, result.version());
        verifyNoInteractions(cache);
    }

    @Test
    void userRoleAndDifferentMerchantCannotUpdateShop() {
        BusinessException userError = assertThrows(BusinessException.class,
                () -> service.update(1L, UPDATE, new UserPrincipal(101L, "用户", "USER")));
        assertEquals("FORBIDDEN", userError.getCode());
        verifyNoInteractions(mapper);

        when(mapper.findById(1L)).thenReturn(shop());
        BusinessException ownerError = assertThrows(BusinessException.class,
                () -> service.update(1L, UPDATE, new UserPrincipal(102L, "其他商户", "MERCHANT")));
        assertEquals("FORBIDDEN", ownerError.getCode());
        verify(mapper, never()).update(anyLong(), anyLong(), any(), any(), any(), anyInt(), any());
        verifyNoInteractions(cache);
    }

    @Test
    void lostOptimisticUpdateReturnsConflictWithoutInvalidatingCache() {
        when(mapper.findById(1L)).thenReturn(shop());
        when(mapper.update(eq(1L), eq(101L), eq("新店名"), eq(null), eq(null), eq(2), any()))
                .thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.update(1L, UPDATE, OWNER));

        assertEquals("VERSION_CONFLICT", exception.getCode());
        assertEquals(409, exception.getStatus().value());
        verifyNoInteractions(cache);
    }

    @Test
    void committedUpdateIncrementsVersionAndDefersEvictionUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        when(mapper.findById(1L)).thenReturn(shop());
        when(mapper.update(eq(1L), eq(101L), eq("新店名"), eq(null), eq(null), eq(2), any()))
                .thenReturn(1);

        ShopView result = service.update(1L, UPDATE, OWNER);

        assertEquals(3, result.version());
        assertEquals("新店名", result.name());
        assertEquals(LocalDateTime.of(2026, 9, 30, 1, 0), result.updatedAt());
        verifyNoInteractions(cache);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(cache).evict(1L);
    }

    @Test
    void transactionRollbackDoesNotInvalidateCache() {
        TransactionSynchronizationManager.initSynchronization();
        when(mapper.findById(1L)).thenReturn(shop());
        when(mapper.update(eq(1L), eq(101L), eq("新店名"), eq(null), eq(null), eq(2), any()))
                .thenReturn(1);

        service.update(1L, UPDATE, OWNER);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(cache);
    }

    private static ShopRow shop() {
        return new ShopRow(1L, 101L, "咖啡店", "东校区", "咖啡", 1800,
                1, 2, "校园咖啡", "一号楼", LocalDateTime.of(2026, 1, 1, 0, 0));
    }
}
