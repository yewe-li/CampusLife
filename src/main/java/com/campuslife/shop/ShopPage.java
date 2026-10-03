package com.campuslife.shop;

import java.util.List;

public record ShopPage(List<ShopView> items, long total, int page, int size) {
    public ShopPage {
        items = List.copyOf(items);
    }
}
