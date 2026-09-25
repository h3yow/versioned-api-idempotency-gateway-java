package com.github.h3yow.versionedapiidempotencygatewayjava.auth;

import java.util.Set;

/**
 * 本地登记的客户端档案（无真实凭据）：标识、名称与能力范围。
 */
public record ClientProfile(String clientId, String displayName, Set<String> scopes) {
}
