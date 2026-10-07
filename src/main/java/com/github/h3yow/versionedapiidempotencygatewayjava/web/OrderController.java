package com.github.h3yow.versionedapiidempotencygatewayjava.web;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.RequestContext;
import com.github.h3yow.versionedapiidempotencygatewayjava.gateway.GatewayResult;
import com.github.h3yow.versionedapiidempotencygatewayjava.gateway.GatewayService;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 统一调用入口：创建/查询订单，所有决策经由网关编排保持一致。
 *
 * <p>稳定响应头：
 * <ul>
 *     <li>X-Request-Id：请求标识（客户端可传 X-Request-Id 关联，否则服务端生成）；</li>
 *     <li>X-Api-Version-Selected：实际选中的处理版本；</li>
 *     <li>Deprecation / Sunset：命中已弃用版本时的明确弃用信号；</li>
 *     <li>X-Idempotency-Outcome：幂等处理结论（EXECUTED/REPLAYED/...）。</li>
 * </ul>
 */
@RestController
public class OrderController {

    private final GatewayService gatewayService;

    public OrderController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping("/api/orders")
    public ResponseEntity<Object> create(
            @RequestHeader(value = "X-Api-Version", required = false) String version,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String clientRequestId,
            @RequestBody(required = false) CreateOrderRequest request) {
        RequestContext context = toContext(version, clientId, idempotencyKey, clientRequestId);
        GatewayResult<Object> result = gatewayService.createOrder(context, request);
        return ResponseEntity.ok().headers(commonHeaders(context, result)).body(result.body());
    }

    @GetMapping("/api/orders/{orderId}")
    public ResponseEntity<Object> get(
            @RequestHeader(value = "X-Api-Version", required = false) String version,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId,
            @RequestHeader(value = "X-Request-Id", required = false) String clientRequestId,
            @PathVariable String orderId) {
        RequestContext context = toContext(version, clientId, null, clientRequestId);
        return gatewayService.getOrder(context, orderId)
                .map(result -> ResponseEntity.ok().headers(commonHeaders(context, result)).body(result.body()))
                .orElseGet(() -> ResponseEntity.notFound()
                        .header("X-Request-Id", context.requestId())
                        .build());
    }

    static HttpHeaders commonHeaders(RequestContext context, GatewayResult<?> result) {
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

    static RequestContext toContext(String version, String clientId, String idempotencyKey,
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
}
