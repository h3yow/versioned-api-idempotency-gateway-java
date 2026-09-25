package com.github.h3yow.versionedapiidempotencygatewayjava.web.handler;

import com.github.h3yow.versionedapiidempotencygatewayjava.exception.ErrorType;
import com.github.h3yow.versionedapiidempotencygatewayjava.exception.GatewayException;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.CreateOrderRequest;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.CreateOrderResponse;
import com.github.h3yow.versionedapiidempotencygatewayjava.version.VersionedHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v2 主版本订单处理路径（当前版本）。
 * 业务失败/校验规则与 v1 保持一致，但订单标识与处理路径版本不同，
 * 用于验证版本协商确实选择了兼容的处理路径。
 *
 * <p>对 item == "FAIL-ONCE" 的订单，同一业务键只在首次执行时失败，
 * 同键同体重试即可成功，用于演示"失败后重试"语义。
 */
@Component
public class V2OrderHandler implements VersionedHandler {

    /**
     * 仅用于并发测试的阻塞钩子：处理 item == "BLOCKING" 的请求时执行。
     * 默认空操作；测试可设置为在业务动作中途等待，从而制造 IN_PROGRESS 窗口。
     * 生产路径永不设置此字段。
     */
    public static volatile Runnable testBlocker = () -> { };

    private final OrderSideEffectCounter sideEffectCounter;
    private final Map<String, AtomicLong> failureAttempts = new ConcurrentHashMap<>();

    public V2OrderHandler(OrderSideEffectCounter sideEffectCounter) {
        this.sideEffectCounter = sideEffectCounter;
    }

    @Override
    public int supportedMajor() {
        return 2;
    }

    @Override
    public Object handle(Object request, String requestId) {
        CreateOrderRequest body = (CreateOrderRequest) request;
        validate(body);
        if ("BLOCKING".equalsIgnoreCase(body.item())) {
            testBlocker.run();
        }
        if ("FAIL".equalsIgnoreCase(body.item())) {
            throw new GatewayException(ErrorType.BUSINESS_ERROR,
                    "order processing failed (deterministic failure branch)",
                    Map.of("item", body.item(), "handlerVersion", "2"));
        }
        if ("FAIL-ONCE".equalsIgnoreCase(body.item())) {
            String businessKey = body.item() + ":" + body.quantity();
            long attempt = failureAttempts.computeIfAbsent(businessKey, k -> new AtomicLong())
                    .incrementAndGet();
            if (attempt == 1) {
                throw new GatewayException(ErrorType.BUSINESS_ERROR,
                        "transient failure on first attempt; retry with same key should succeed",
                        Map.of("item", body.item(), "attempt", attempt, "handlerVersion", "2"));
            }
        }
        sideEffectCounter.increment();
        return new CreateOrderResponse(
                "v2-order-" + Math.abs((body.item() + ":" + body.quantity()).hashCode()),
                body.item(),
                body.quantity(),
                "2",
                Instant.now());
    }

    private void validate(CreateOrderRequest body) {
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
