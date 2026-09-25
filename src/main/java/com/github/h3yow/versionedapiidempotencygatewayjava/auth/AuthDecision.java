package com.github.h3yow.versionedapiidempotencygatewayjava.auth;

/**
 * 鉴权结论：是否认证、是否授权及判定依据。
 */
public record AuthDecision(
        boolean authenticated,
        boolean authorized,
        String clientId,
        String requiredScope,
        String reason) {
}
