package com.github.h3yow.versionedapiidempotencygatewayjava.auth;

import com.github.h3yow.versionedapiidempotencygatewayjava.model.ClientProfile;

import java.util.Optional;

/** 本地客户端目录（不依赖外部服务或真实凭据）。 */
public interface ClientDirectory {

    Optional<ClientProfile> find(String clientId);
}
