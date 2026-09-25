package com.github.h3yow.versionedapiidempotencygatewayjava.auth;

import com.github.h3yow.versionedapiidempotencygatewayjava.model.ClientCapability;

/**
 * 按客户端能力范围进行访问判定。
 *
 * <p>判定依据（reason）区分三种情况，便于审计与排查：
 * <ul>
 *   <li>未提供客户端标识 → "missing client identity"</li>
 *   <li>客户端未在目录中注册 → "unknown client"</li>
 *   <li>客户端存在但缺少能力 → "capability not granted"</li>
 *   <li>放行 → "capability granted"</li>
 * </ul>
 * 注意：对外响应不暴露资源是否存在，拒绝统一由上层映射为 RESOURCE_NOT_FOUND。
 */
public class AuthorizationService {

    private final ClientDirectory directory;

    public AuthorizationService(ClientDirectory directory) {
        this.directory = directory;
    }

    public AuthorizationDecision authorize(String clientId, ClientCapability capability) {
        if (clientId == null || clientId.isBlank()) {
            return new AuthorizationDecision(false,
                    clientId == null ? "<anonymous>" : clientId,
                    capability, "missing client identity");
        }
        String normalized = clientId.trim();
        return directory.find(normalized)
                .map(profile -> profile.capabilities().contains(capability)
                        ? new AuthorizationDecision(true, normalized, capability, "capability granted")
                        : new AuthorizationDecision(false, normalized, capability,
                            "capability not granted (client capabilities: " + profile.capabilities() + ")"))
                .orElseGet(() -> new AuthorizationDecision(false, normalized, capability, "unknown client"));
    }
}
