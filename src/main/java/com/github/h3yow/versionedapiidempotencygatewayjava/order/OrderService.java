package com.github.h3yow.versionedapiidempotencygatewayjava.order;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiException;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ErrorCode;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderRequest;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单业务能力：创建（产生一次副作用）与查询。
 *
 * <p>{@link #sideEffectCount()} 记录真实副作用次数，供测试断言“仅发生一次”。
 * 业务规则：数量必须为正；商品名 "forbidden-product" 触发可解释的业务异常
 * （用于失败后重试场景，失败本身不产生副作用）。
 *
 * <p>产生的是与对外版本无关的完整结果 {@link CreateOrderResult}；精简/扩展返回
 * 由 OrderResponsePresenter 按协商版本渲染。
 */
@Service
public class OrderService {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"));

    private final Map<String, CreateOrderResult> orders = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger sideEffectCount = new AtomicInteger();

    public CreateOrderResult create(CreateOrderRequest request) {
        if (request == null || request.product() == null || request.product().isBlank()) {
            throw new ApiException(ErrorCode.BUSINESS_ERROR, "商品名称不能为空");
        }
        if (request.quantity() <= 0) {
            throw new ApiException(ErrorCode.BUSINESS_ERROR,
                    "下单数量必须为正数，实际为 " + request.quantity());
        }
        if ("forbidden-product".equalsIgnoreCase(request.product().trim())) {
            throw new ApiException(ErrorCode.BUSINESS_ERROR,
                    "商品 '" + request.product() + "' 当前不可下单（业务规则拒绝）");
        }

        // —— 以下为唯一的业务副作用：生成订单并落库 ——
        String orderId = "ORD-" + String.format("%08d", sequence.incrementAndGet());
        CreateOrderResult result = new CreateOrderResult(
                orderId, request.product(), request.quantity(), "CREATED",
                request.amount(), TIME.format(Instant.now()));
        orders.put(orderId, result);
        sideEffectCount.incrementAndGet();
        return result;
    }

    public Optional<CreateOrderResult> findById(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    public int sideEffectCount() {
        return sideEffectCount.get();
    }

    public void reset() {
        orders.clear();
        sequence.set(0);
        sideEffectCount.set(0);
    }
}
