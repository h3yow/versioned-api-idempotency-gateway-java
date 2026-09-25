package com.github.h3yow.versionedapiidempotencygatewayjava.model;

import java.util.Set;

/** 已注册客户端及其能力范围。 */
public record ClientProfile(String clientId, Set<ClientCapability> capabilities) {
}
