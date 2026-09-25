package com.github.h3yow.versionedapiidempotencygatewayjava.auth;

import org.springframework.stereotype.Component;

/**
 * 权限判定：按客户端能力范围（scope）决定访问。
 *
 * <p>判定分两步且结论都保留依据：认证（客户端是否在本地目录登记）、
 * 授权（登记客户端是否持有资源要求的 scope）。
 */
@Component
public class AuthorizationService {

    private final ClientDirectory directory;

    public AuthorizationService(ClientDirectory directory) {
        this.directory = directory;
    }

    public AuthDecision authenticate(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return new AuthDecision(false, false, null, null,
                    "缺少 X-Client-Id 请求头，无法识别调用方");
        }
        ClientProfile profile = directory.find(clientId);
        if (profile == null) {
            return new AuthDecision(false, false, clientId, null,
                    "客户端 '" + clientId + "' 未在本地客户端目录登记");
        }
        return new AuthDecision(true, false, clientId, null,
                "客户端 '" + clientId + "'（" + profile.displayName() + "）认证通过");
    }

    public AuthDecision authorize(ClientProfile client, String requiredScope) {
        boolean granted = client.scopes().contains(requiredScope);
        String reason = granted
                ? "客户端 '" + client.clientId() + "' 持有能力范围 " + requiredScope
                : "客户端 '" + client.clientId() + "' 缺少能力范围 " + requiredScope
                        + "（现有范围 " + client.scopes() + "）";
        return new AuthDecision(true, granted, client.clientId(), requiredScope, reason);
    }
}
