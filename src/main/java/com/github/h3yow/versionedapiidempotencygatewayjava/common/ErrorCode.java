package com.github.h3yow.versionedapiidempotencygatewayjava.common;

/**
 * 稳定的错误契约编码：每种错误拥有稳定 code 与 HTTP 状态。
 */
public enum ErrorCode {
    VERSION_MISSING(400),
    VERSION_UNKNOWN(404),
    VERSION_INCOMPATIBLE(400),
    IDEMPOTENCY_KEY_MISSING(400),
    IDEMPOTENCY_IN_PROGRESS(409),
    IDEMPOTENCY_KEY_CONFLICT(409),
    AUTH_UNAUTHENTICATED(401),
    AUTH_FORBIDDEN(403),
    RESOURCE_NOT_FOUND(404),
    CAPABILITY_UNKNOWN(404),
    OPERATION_UNKNOWN(404),
    INVOCATION_INVALID(400),
    BUSINESS_ERROR(422),
    INTERNAL(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
