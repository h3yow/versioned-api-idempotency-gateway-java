package com.github.h3yow.versionedapiidempotencygatewayjava.model;

/** 统一成功响应契约。 */
public record GatewayResponse<T>(
        String requestId,
        String resolvedVersion,
        boolean deprecated,
        IdempotencyInfo idempotency,
        T data
) {
    public record IdempotencyInfo(String key, String status, boolean replayed) {
    }
}
