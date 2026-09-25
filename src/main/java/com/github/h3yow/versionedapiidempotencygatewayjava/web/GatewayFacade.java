package com.github.h3yow.versionedapiidempotencygatewayjava.web;

import com.github.h3yow.versionedapiidempotencygatewayjava.model.ClientCapability;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.GatewayResponse;

/**
 * 所有调用入口（REST / 内部门面）共用的统一网关：
 * 版本协商 -> 权限判定 -> 幂等执行 -> 审计，顺序与错误契约一致。
 */
public interface GatewayFacade {

    GatewayResponse<Object> invoke(
            String rawVersion,
            String clientId,
            String idempotencyKey,
            ClientCapability capability,
            Object requestBody,
            String requestId
    );
}
