package com.github.h3yow.versionedapiidempotencygatewayjava.audit;

/**
 * 可审计决策记录：请求标识、版本决策、幂等结果、权限判定依据。
 */
public record AuditEntry(
        String requestId,
        String clientId,
        String capability,
        String declaredVersion,
        String versionDecision,
        String idempotencyOutcome,
        String authDecision,
        String result,
        long timestampMs) {
}
