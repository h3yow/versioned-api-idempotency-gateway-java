package com.github.h3yow.versionedapiidempotencygatewayjava.web;

import com.github.h3yow.versionedapiidempotencygatewayjava.auth.AuthorizationService;
import com.github.h3yow.versionedapiidempotencygatewayjava.auth.ClientDirectory;
import com.github.h3yow.versionedapiidempotencygatewayjava.auth.InMemoryClientDirectory;
import com.github.h3yow.versionedapiidempotencygatewayjava.idem.IdempotencyStore;
import com.github.h3yow.versionedapiidempotencygatewayjava.idem.InMemoryIdempotencyStore;
import com.github.h3yow.versionedapiidempotencygatewayjava.version.InMemoryVersionRegistry;
import com.github.h3yow.versionedapiidempotencygatewayjava.version.VersionRegistry;
import com.github.h3yow.versionedapiidempotencygatewayjava.version.VersionedHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 本地组件接线，全部使用内存实现，不依赖外部服务或真实凭据。 */
@Configuration
public class GatewayConfiguration {

    @Bean
    public ClientDirectory clientDirectory() {
        return new InMemoryClientDirectory();
    }

    @Bean
    public AuthorizationService authorizationService(ClientDirectory directory) {
        return new AuthorizationService(directory);
    }

    @Bean
    public VersionRegistry versionRegistry(List<VersionedHandler> handlers) {
        return new InMemoryVersionRegistry(handlers);
    }

    @Bean
    public IdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }
}
