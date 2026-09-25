package com.github.h3yow.versionedapiidempotencygatewayjava.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地客户端目录：内置演示客户端及其能力范围（不依赖外部身份服务或真实凭据）。
 *
 * <ul>
 *     <li>client-mobile-1：读写齐全的受支持客户端；</li>
 *     <li>client-legacy-1：仅能声明 1.x 的旧客户端，仍具读写能力；</li>
 *     <li>client-readonly-1：只读能力；</li>
 *     <li>client-noaccess-1：已登记但无任何能力范围。</li>
 * </ul>
 */
@Component
public class ClientDirectory {

    private final Map<String, ClientProfile> clients = new ConcurrentHashMap<>();

    @PostConstruct
    void seed() {
        register(new ClientProfile("client-mobile-1", "移动客户端 1 号",
                Set.of("orders:write", "orders:read")));
        register(new ClientProfile("client-legacy-1", "遗留客户端 1 号",
                Set.of("orders:write", "orders:read")));
        register(new ClientProfile("client-readonly-1", "只读客户端 1 号",
                Set.of("orders:read")));
        register(new ClientProfile("client-noaccess-1", "无权限客户端 1 号",
                Set.of()));
    }

    public void register(ClientProfile profile) {
        clients.put(profile.clientId(), profile);
    }

    public ClientProfile find(String clientId) {
        return clientId == null ? null : clients.get(clientId);
    }
}
