package com.github.h3yow.versionedapiidempotencygatewayjava.model;

/** 版本协商决策（可审计）。 */
public record VersionDecision(
        VersionStatus status,
        ApiVersion requested,
        ApiVersion resolved,
        boolean deprecated,
        String reason
) {
}
