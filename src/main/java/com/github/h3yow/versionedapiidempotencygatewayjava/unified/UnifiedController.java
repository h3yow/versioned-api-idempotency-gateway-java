package com.github.h3yow.versionedapiidempotencygatewayjava.unified;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.RequestContext;
import com.github.h3yow.versionedapiidempotencygatewayjava.gateway.GatewayResult;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderResult;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.view.OrderResponsePresenter;
import com.github.h3yow.versionedapiidempotencygatewayjava.web.GatewayHttpSupport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * 内部统一调用入口：{@code POST /api/internal/invoke}。
 *
 * <p>请求体形如：
 * <pre>{@code
 * { "capability": "orders",
 *   "operation": "createOrder",
 *   "params": { "product": "widget", "quantity": 2, "amount": 19.99 } }
 * }</pre>
 *
 * <p>与业务端点共用同一套鉴权、版本协商、幂等与异常返回：同样的输入两边结果对得上
 * （拒绝原因、HTTP 状态、选中版本、弃用提示、幂等结论一致）；响应头也完全相同。
 */
@RestController
public class UnifiedController {

    private final UnifiedGatewayService unifiedGatewayService;
    private final OrderResponsePresenter presenter;

    public UnifiedController(UnifiedGatewayService unifiedGatewayService,
                             OrderResponsePresenter presenter) {
        this.unifiedGatewayService = unifiedGatewayService;
        this.presenter = presenter;
    }

    @PostMapping("/api/internal/invoke")
    public ResponseEntity<Object> invoke(
            @RequestHeader(value = "X-Api-Version", required = false) String version,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String clientRequestId,
            @RequestBody(required = false) UnifiedInvokeRequest request) {

        RequestContext context = GatewayHttpSupport.toContext(
                version, clientId, idempotencyKey, clientRequestId);

        // 认证先于能力/操作识别：未认证统一 401，不暴露能力或资源是否存在。
        unifiedGatewayService.requireAuthenticated(context);

        if (unifiedGatewayService.isReadOperation(request)) {
            Optional<GatewayResult<?>> found = unifiedGatewayService.invokeRead(context, request);
            return found.map(result -> ResponseEntity.ok()
                            .headers(GatewayHttpSupport.commonHeaders(context, result))
                            .body(render(result)))
                    .orElseGet(() -> ResponseEntity.notFound()
                            .header("X-Request-Id", context.requestId())
                            .build());
        }

        // 写操作（含未知能力/操作的稳定拒绝）：validateKnown 抛 CAPABILITY_UNKNOWN/
        // OPERATION_UNKNOWN/INVOKE_REQUEST_INVALID，由统一异常映射处理。
        GatewayResult<?> result = unifiedGatewayService.invoke(context, request);
        return ResponseEntity.ok()
                .headers(GatewayHttpSupport.commonHeaders(context, result))
                .body(render(result));
    }

    private Object render(GatewayResult<?> result) {
        Object body = result.body();
        if (body instanceof CreateOrderResult order) {
            return presenter.render(order, result.selectedVersion());
        }
        return body;
    }
}
