package com.github.h3yow.versionedapiidempotencygatewayjava.exception;

/**
 * 稳定可区分的错误类型。每种错误具有稳定 code 与 HTTP 状态，
 * 且可携带额外响应头（如弃用信号）。
 */
public enum ErrorType {
    VERSION_MISSING("VERSION_MISSING", 400),
    VERSION_UNKNOWN("VERSION_UNKNOWN", 400),
    VERSION_INCOMPATIBLE("VERSION_INCOMPATIBLE", 400),
    IDEMPOTENCY_IN_PROGRESS("IDEMPOTENCY_IN_PROGRESS", 409),
    IDEMPOTENCY_KEY_CONFLICT("IDEMPOTENCY_KEY_CONFLICT", 409),
    IDEMPOTENCY_KEY_MISSING("IDEMPOTENCY_KEY_MISSING", 400),
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", 404),
    BUSINESS_ERROR("BUSINESS_ERROR", 422),
    VALIDATION_ERROR("VALIDATION_ERROR", 400),
    INTERNAL_ERROR("INTERNAL_ERROR", 500);

    private final String code;
    private final int httpStatus;

    ErrorType(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
