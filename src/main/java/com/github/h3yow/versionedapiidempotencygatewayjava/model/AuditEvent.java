package com.github.h3yow.versionedapiidempotencygatewayjava.model;

import java.time.Instant;

/** 一次调用的审计记录。 */
public record AuditEvent(
        Instant timestamp,
        String requestId,
        String clientId,
        String capability,
        String rawVersion,
        String versionDecision,
        String idempotencyStatus,
        String authorization,
        String outcome
) {
}
