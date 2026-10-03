package com.campuslife.shop;

import java.time.LocalDateTime;

/** Public shop fields; ownership and private voucher data are never cached. */
public record ShopView(long id, String name, String campus, String category,
                       int averagePrice, int status, int version,
                       String description, String address, LocalDateTime updatedAt) {
    static ShopView from(ShopRow row) {
        return new ShopView(row.id(), row.name(), row.campus(), row.category(),
                row.averagePrice(), row.status(), row.version(), row.description(),
                row.address(), row.updatedAt());
    }
}
