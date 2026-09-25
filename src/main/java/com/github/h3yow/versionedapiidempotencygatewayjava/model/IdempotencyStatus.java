package com.github.h3yow.versionedapiidempotencygatewayjava.model;

/** 幂等处理状态。 */
public enum IdempotencyStatus {
    NEW,
    IN_PROGRESS,
    REPLAY_SUCCEEDED,
    RETRY_AFTER_FAILURE,
    KEY_CONFLICT
}
