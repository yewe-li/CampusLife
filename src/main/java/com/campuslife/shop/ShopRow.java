package com.campuslife.shop;

import java.time.LocalDateTime;

public record ShopRow(long id, long merchantId, String name, String campus, String category,
                      int averagePrice, int status, int version,
                      String description, String address, LocalDateTime updatedAt) {
}
