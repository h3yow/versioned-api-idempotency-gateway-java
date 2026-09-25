package com.github.h3yow.versionedapiidempotencygatewayjava.idempotency;

/**
 * 幂等记录生命周期状态。
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED
}
