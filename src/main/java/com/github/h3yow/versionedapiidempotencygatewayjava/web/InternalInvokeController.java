package com.github.h3yow.versionedapiidempotencygatewayjava.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiException;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ErrorCode;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.RequestContext;
import com.github.h3yow.versionedapiidempotencygatewayjava.gateway.GatewayResult;
import com.github.h3yow.versionedapiidempotencygatewayjava.gateway.GatewayService;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderRequest;
import com.github.h3yow.versionedapiidempotencygatewayjava.web.dto.InvokeRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * 内部统一调用入口：内部服务把要调的能力、操作和参数一起提交到本入口，
 * 不必按业务接口一个个对接。
 *
 * <p>本入口与业务接口（/api/orders...）共用同一个 {@link GatewayService} 编排，
 * 因此鉴权、版本协商、幂等与异常返回完全一致：同样的输入在两边得到同样的
 * 拒绝原因、状态语义、选中版本、弃用提示与幂等结论；未授权查询同样以 404
 * 隐藏资源存在性。
 *
 * <p>请求信封：{@code {"capability":"orders","operation":"create|get","params":{...}}}；
 * 版本、客户端、幂等键与请求标识仍走与业务接口相同的请求头。
 */
@RestController
public class InternalInvokeController {

    private static final String OP_CREATE = "create";
    private static final String OP_GET = "get";

    private final GatewayService gatewayService;
    private final ObjectMapper objectMapper;

    public InternalInvokeController(GatewayService gatewayService, ObjectMapper objectMapper) {
        this.gatewayService = gatewayService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/internal/invoke")
    public ResponseEntity<Object> invoke(
            @RequestHeader(value = "X-Api-Version", required = false) String version,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String clientRequestId,
            @RequestBody(required = false) InvokeRequest request) {
        if (request == null || request.capability() == null || request.capability().isBlank()
                || request.operation() == null || request.operation().isBlank()) {
            throw new ApiException(ErrorCode.INVOCATION_INVALID,
                    "统一调用信封必须包含 capability 与 operation");
        }
        RequestContext context =
                OrderController.toContext(version, clientId, idempotencyKey, clientRequestId);
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();

        return switch (request.capability().trim()) {
            case GatewayService.CAPABILITY_ORDER -> switch (request.operation().trim()) {
                case OP_CREATE -> createOrder(context, params);
                case OP_GET -> getOrder(context, params);
                default -> throw unknownOperation(request);
            };
            default -> throw new ApiException(ErrorCode.CAPABILITY_UNKNOWN,
                    "能力 '" + request.capability() + "' 未在网关登记");
        };
    }

    private ResponseEntity<Object> createOrder(RequestContext context, Map<String, Object> params) {
        final CreateOrderRequest orderRequest;
        try {
            orderRequest = objectMapper.convertValue(params, CreateOrderRequest.class);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVOCATION_INVALID,
                    "orders.create 的参数无法解析为下单请求：" + ex.getMessage());
        }
        GatewayResult<Object> result = gatewayService.createOrder(context, orderRequest);
        return ResponseEntity.ok()
                .headers(OrderController.commonHeaders(context, result))
                .body(result.body());
    }

    private ResponseEntity<Object> getOrder(RequestContext context, Map<String, Object> params) {
        Object orderId = params.get("orderId");
        if (orderId == null || orderId.toString().isBlank()) {
            throw new ApiException(ErrorCode.INVOCATION_INVALID,
                    "orders.get 缺少参数 orderId");
        }
        // 与 GET /api/orders/{id} 同构：未授权或不存在都表现为 404，不泄露存在性
        Optional<GatewayResult<Object>> result = gatewayService.getOrder(context, orderId.toString());
        return result
                .map(r -> ResponseEntity.ok()
                        .headers(OrderController.commonHeaders(context, r))
                        .body((Object) r.body()))
                .orElseGet(() -> ResponseEntity.notFound()
                        .header("X-Request-Id", context.requestId())
                        .build());
    }

    private static ApiException unknownOperation(InvokeRequest request) {
        return new ApiException(ErrorCode.OPERATION_UNKNOWN,
                "能力 '" + request.capability() + "' 不支持操作 '" + request.operation() + "'");
    }
}
