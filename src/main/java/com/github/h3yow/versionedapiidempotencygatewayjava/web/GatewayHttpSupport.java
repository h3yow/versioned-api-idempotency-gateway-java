package com.github.h3yow.versionedapiidempotencygatewayjava.web;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.RequestContext;
import com.github.h3yow.versionedapiidempotencygatewayjava.gateway.GatewayResult;
import org.springframework.http.HttpHeaders;

import java.util.UUID;

/**
 * 两个 HTTP 入口（业务端点与统一调用入口）共用的支持逻辑：
 * 请求上下文构造与稳定响应头组装，确保两边结果对得上。
 */
public final class GatewayHttpSupport {

    private GatewayHttpSupport() {
    }

    /** 由请求头构造调用上下文；优先使用过滤器写入 MDC 的请求标识。 */
    public static RequestContext toContext(String version, String clientId, String idempotencyKey,
                                           String clientRequestId) {
        String requestId = org.slf4j.MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId == null) {
            requestId = (clientRequestId == null || clientRequestId.isBlank())
                    ? UUID.randomUUID().toString()
                    : clientRequestId.trim();
        }
        return new RequestContext(requestId,
                version == null ? null : version.trim(),
                clientId == null ? null : clientId.trim(),
                idempotencyKey == null ? null : idempotencyKey.trim());
    }

    /**
     * 稳定响应头：X-Request-Id、X-Api-Version-Selected、
     * Deprecation/Sunset（弃用）、X-Idempotency-Outcome。
     * 业务端点与统一入口必须产出完全一致的头。
     */
    public static HttpHeaders commonHeaders(RequestContext context, GatewayResult<?> result) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Request-Id", context.requestId());
        headers.add("X-Api-Version-Selected", result.selectedVersion());
        if (result.deprecated()) {
            headers.add("Deprecation", "true");
            if (result.sunset() != null) {
                headers.add("Sunset", result.sunset());
            }
        }
        if (result.idempotencyOutcome() != null) {
            headers.add("X-Idempotency-Outcome", result.idempotencyOutcome());
        }
        return headers;
    }
}
