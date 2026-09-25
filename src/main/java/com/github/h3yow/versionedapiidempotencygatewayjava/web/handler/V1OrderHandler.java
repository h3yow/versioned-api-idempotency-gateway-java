package com.github.h3yow.versionedapiidempotencygatewayjava.web.handler;

import com.github.h3yow.versionedapiidempotencygatewayjava.exception.ErrorType;
import com.github.h3yow.versionedapiidempotencygatewayjava.exception.GatewayException;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.CreateOrderRequest;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.CreateOrderResponse;
import com.github.h3yow.versionedapiidempotencygatewayjava.version.VersionedHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * v1 主版本订单处理路径（兼容 1.0 / 1.9 旧客户端）。
 *
 * <p>业务规则：
 * <ul>
 *   <li>item 为空 → VALIDATION_ERROR</li>
 *   <li>quantity &lt;= 0 → VALIDATION_ERROR</li>
 *   <li>item == "FAIL" → 确定性 BUSINESS_ERROR（供失败后重试测试）</li>
 *   <li>否则创建订单，副作用计数 +1，返回结果</li>
 * </ul>
 * 订单 ID 由业务内容确定性生成，使首次与重试/回放返回一致结果。
 */
@Component
public class V1OrderHandler implements VersionedHandler {

    private final OrderSideEffectCounter sideEffectCounter;

    public V1OrderHandler(OrderSideEffectCounter sideEffectCounter) {
        this.sideEffectCounter = sideEffectCounter;
    }

    @Override
    public int supportedMajor() {
        return 1;
    }

    @Override
    public Object handle(Object request, String requestId) {
        CreateOrderRequest body = (CreateOrderRequest) request;
        validate(body);
        if ("FAIL".equalsIgnoreCase(body.item())) {
            throw new GatewayException(ErrorType.BUSINESS_ERROR,
                    "order processing failed (deterministic failure branch)",
                    Map.of("item", body.item()));
        }
        long sideEffectNo = sideEffectCounter.increment();
        return new CreateOrderResponse(
                "v1-order-" + Math.abs((body.item() + ":" + body.quantity()).hashCode()),
                body.item(),
                body.quantity(),
                "1",
                Instant.now());
    }

    static void validate(CreateOrderRequest body) {
        if (body == null) {
            throw new GatewayException(ErrorType.VALIDATION_ERROR, "request body is required");
        }
        if (body.item() == null || body.item().isBlank()) {
            throw new GatewayException(ErrorType.VALIDATION_ERROR, "item must not be blank");
        }
        if (body.quantity() == null || body.quantity() <= 0) {
            throw new GatewayException(ErrorType.VALIDATION_ERROR, "quantity must be positive",
                    Map.of("quantity", body.quantity() == null ? null : body.quantity()));
        }
    }
}
