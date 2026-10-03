package com.campuslife.shop;

import com.campuslife.auth.UserPrincipal;
import com.campuslife.common.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ShopService {
    private final ShopMapper mapper;
    private final ShopCache cache;
    private final Clock clock;

    public ShopService(ShopMapper mapper, ShopCache cache, Clock clock) {
        this.mapper = mapper;
        this.cache = cache;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ShopPage list(String campus, String category, Integer maxPrice, int page, int size) {
        validatePage(page, size);
        if (maxPrice != null && (maxPrice < 0 || maxPrice > 10_000_000)) {
            throw badRequest("maxPrice 范围为 0–10000000 分");
        }
        ShopFilter filter = new ShopFilter(filterText(campus), filterText(category), maxPrice);
        List<ShopView> items = mapper.list(filter, ((long) page - 1) * size, size)
                .stream().map(ShopView::from).toList();
        long total = mapper.count(filter);
        return new ShopPage(items, total, page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ShopPage listForMerchant(UserPrincipal user, int page, int size) {
        requireMerchant(user);
        validatePage(page, size);
        List<ShopView> items = mapper.listForMerchant(user.id(), ((long) page - 1) * size, size)
                .stream().map(ShopView::from).toList();
        long total = mapper.countForMerchant(user.id());
        return new ShopPage(items, total, page, size);
    }

    public ShopView detailForMerchant(long id, UserPrincipal user) {
        requireMerchant(user);
        validateId(id);
        // Management must include offline shops and read the current database version, not a public cache.
        ShopRow row = mapper.findById(id);
        if (row == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SHOP_NOT_FOUND", "店铺不存在");
        }
        if (row.merchantId() != user.id()) {
            throw forbidden();
        }
        return ShopView.from(row);
    }

    public ShopView detail(long id) {
        validateId(id);
        ShopCache.Lookup cached = cache.lookup(id);
        if (cached.hit()) {
            if (cached.detail() == null) {
                throw notFound();
            }
            return cached.detail();
        }
        ShopRow row = mapper.findOnline(id);
        ShopView detail = row == null ? null : ShopView.from(row);
        if (cached.writable()) {
            cache.store(id, detail);
        }
        if (detail == null) {
            throw notFound();
        }
        return detail;
    }

    @Transactional
    public ShopView update(long id, ShopUpdateRequest request, UserPrincipal user) {
        validateId(id);
        requireMerchant(user);
        validateUpdate(request);
        ShopRow current = mapper.findById(id);
        if (current == null) {
            throw notFound();
        }
        if (current.merchantId() != user.id()) {
            throw forbidden();
        }
        if (current.version() != request.version()) {
            throw conflict();
        }
        String name = request.name() == null ? null : request.name().strip();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int affected = mapper.update(id, user.id(), name, request.averagePrice(), request.status(),
                request.version(), now);
        if (affected != 1) {
            throw conflict();
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.evict(id);
            }
        });
        return new ShopView(current.id(), name == null ? current.name() : name,
                current.campus(), current.category(),
                request.averagePrice() == null ? current.averagePrice() : request.averagePrice(),
                request.status() == null ? current.status() : request.status(),
                current.version() + 1, current.description(), current.address(), now);
    }

    private static void requireMerchant(UserPrincipal user) {
        if (user == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录");
        }
        if (!"MERCHANT".equals(user.role())) {
            throw forbidden();
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 1 || page > 1_000_000 || size < 1 || size > 100) {
            throw badRequest("page 范围为 1–1000000，size 范围为 1–100");
        }
    }

    private static void validateUpdate(ShopUpdateRequest request) {
        if (request == null || request.version() == null || request.version() < 0
                || request.version() == Integer.MAX_VALUE) {
            throw badRequest("必须提供有效 version");
        }
        if (request.name() == null && request.averagePrice() == null && request.status() == null) {
            throw badRequest("至少提供 name、averagePrice 或 status 中的一项");
        }
        if (request.name() != null && (request.name().isBlank() || request.name().length() > 100)) {
            throw badRequest("店铺名称须为 1–100 个字符");
        }
        if (request.averagePrice() != null
                && (request.averagePrice() < 0 || request.averagePrice() > 10_000_000)) {
            throw badRequest("averagePrice 范围为 0–10000000 分");
        }
        if (request.status() != null && request.status() != 0 && request.status() != 1) {
            throw badRequest("status 只能为 0 或 1");
        }
    }

    private static String filterText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.strip();
        if (normalized.length() > 40) {
            throw badRequest("校区或类别不可超过 40 个字符");
        }
        return normalized;
    }

    private static void validateId(long id) {
        if (id <= 0) {
            throw badRequest("店铺编号必须为正整数");
        }
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "SHOP_NOT_FOUND", "店铺不存在或已下线");
    }

    private static BusinessException forbidden() {
        return new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权管理该店铺");
    }

    private static BusinessException conflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "店铺已更新，请刷新后重试");
    }
}
