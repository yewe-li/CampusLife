package com.campuslife.shop;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ShopUpdateRequest(
        @Size(min = 1, max = 100) String name,
        @Min(0) @Max(10_000_000) Integer averagePrice,
        @Min(0) @Max(1) Integer status,
        @NotNull @Min(0) @Max(2_147_483_646) Integer version) {

    // Reject attempts to submit merchantId/owner or other unsupported updates.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("不支持的店铺修改字段");
    }
}
