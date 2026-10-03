package com.campuslife.common;

import org.slf4j.MDC;

public record ApiResponse<T>(String code, String message, T data, String requestId) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "成功", data, MDC.get("requestId"));
    }
}
