package com.github.h3yow.versionedapiidempotencygatewayjava.idem;

/** 幂等存储的判定结果。 */
public record IdempotencyRecordOutcome(
        com.github.h3yow.versionedapiidempotencygatewayjava.model.IdempotencyStatus status,
        IdempotencyRecord record
) {
}
