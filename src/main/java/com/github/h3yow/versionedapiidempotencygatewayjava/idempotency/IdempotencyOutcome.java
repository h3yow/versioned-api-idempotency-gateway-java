package com.github.h3yow.versionedapiidempotencygatewayjava.idempotency;

/**
 * 幂等处理结果类型，用于区分首次执行、成功重放、进行中重试、失败后重试、键冲突。
 */
public enum IdempotencyOutcome {
    EXECUTED,
    REPLAYED,
    IN_PROGRESS,
    RETRY_AFTER_FAILURE,
    KEY_CONFLICT
}
