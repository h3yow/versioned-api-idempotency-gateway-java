package com.github.h3yow.versionedapiidempotencygatewayjava.common;

/**
 * 单次请求的调用上下文（请求标识、声明版本、客户端、幂等键）。
 */
public record RequestContext(
        String requestId,
        String declaredVersion,
        String clientId,
        String idempotencyKey) {
}
