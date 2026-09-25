package com.github.h3yow.versionedapiidempotencygatewayjava.auth;

import com.github.h3yow.versionedapiidempotencygatewayjava.model.ClientCapability;

/** 权限判定结果（可审计）。 */
public record AuthorizationDecision(
        boolean allowed,
        String clientId,
        ClientCapability capability,
        String reason
) {
}
