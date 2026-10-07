package com.github.h3yow.versionedapiidempotencygatewayjava.web;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.RequestContext;
import com.github.h3yow.versionedapiidempotencygatewayjava.gateway.GatewayResult;
import com.github.h3yow.versionedapiidempotencygatewayjava.gateway.GatewayService;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderRequest;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderResult;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.view.OrderResponsePresenter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务调用入口：创建/查询订单，所有决策经由网关编排保持一致。
 *
 * <p>返回形态按 {@code X-Api-Version-Selected} 的主版本渲染（1.x 精简、2.x/3.x 扩展），
 * 渲染器与统一调用入口共用，保证同版本结果一致。
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
    private final OrderResponsePresenter presenter;

    public OrderController(GatewayService gatewayService, OrderResponsePresenter presenter) {
        this.gatewayService = gatewayService;
        this.presenter = presenter;
    }

    @PostMapping("/api/orders")
    public ResponseEntity<Object> create(
            @RequestHeader(value = "X-Api-Version", required = false) String version,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String clientRequestId,
            @RequestBody(required = false) CreateOrderRequest request) {
        RequestContext context = GatewayHttpSupport.toContext(
                version, clientId, idempotencyKey, clientRequestId);
        GatewayResult<CreateOrderResult> result = gatewayService.createOrder(context, request);
        Object view = presenter.render(result.body(), result.selectedVersion());
        return ResponseEntity.ok()
                .headers(GatewayHttpSupport.commonHeaders(context, result))
                .body(view);
    }

    @GetMapping("/api/orders/{orderId}")
    public ResponseEntity<Object> get(
            @RequestHeader(value = "X-Api-Version", required = false) String version,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId,
            @RequestHeader(value = "X-Request-Id", required = false) String clientRequestId,
            @PathVariable String orderId) {
        RequestContext context = GatewayHttpSupport.toContext(
                version, clientId, null, clientRequestId);
        return gatewayService.getOrder(context, orderId)
                .map(result -> ResponseEntity.ok()
                        .headers(GatewayHttpSupport.commonHeaders(context, result))
                        .body((Object) presenter.render(result.body(), result.selectedVersion())))
                .orElseGet(() -> ResponseEntity.notFound()
                        .header("X-Request-Id", context.requestId())
                        .build());
    }
}
