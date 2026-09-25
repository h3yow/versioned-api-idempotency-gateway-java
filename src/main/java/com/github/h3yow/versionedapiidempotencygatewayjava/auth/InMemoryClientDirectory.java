package com.github.h3yow.versionedapiidempotencygatewayjava.auth;

import com.github.h3yow.versionedapiidempotencygatewayjava.model.ClientCapability;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.ClientProfile;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 内存客户端目录。纯本地固定配置，不依赖外部服务或真实凭据：
 * <ul>
 *   <li>client-a：具备下单能力</li>
 *   <li>client-b：已注册但无下单能力（用于能力范围判定测试）</li>
 * </ul>
 */
public class InMemoryClientDirectory implements ClientDirectory {

    private final Map<String, ClientProfile> clients;

    public InMemoryClientDirectory() {
        this.clients = Map.of(
                "client-a", new ClientProfile("client-a", Set.of(ClientCapability.ORDER_CREATE)),
                "client-b", new ClientProfile("client-b", Set.of())
        );
    }

    @Override
    public Optional<ClientProfile> find(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(clients.get(clientId.trim()));
    }
}
