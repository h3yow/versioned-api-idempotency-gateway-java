package com.github.h3yow.versionedapiidempotencygatewayjava.idempotency;

/**
 * 幂等入口的返回结论。
 */
public record IdempotencyResult<T>(
        IdempotencyOutcome outcome,
        T value,
        String errorCode,
        String errorMessage) {
}
