package com.github.h3yow.versionedapiidempotencygatewayjava.gateway;

/**
 * 网关编排结果：业务负载与需要透传给调用入口的信号（弃用/幂等）。
 */
public record GatewayResult<T>(
        T body,
        boolean deprecated,
        String sunset,
        String selectedVersion,
        String idempotencyOutcome) {
}
