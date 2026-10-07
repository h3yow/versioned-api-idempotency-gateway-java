package com.github.h3yow.versionedapiidempotencygatewayjava.order.view;

import java.math.BigDecimal;

/**
 * 主版本 2.x 的扩展下单返回结构：金额、时间戳、服务端追踪等扩展信息。
 */
public record OrderViewV2(
        String orderId,
        String product,
        int quantity,
        String status,
        BigDecimal amount,
        String createdAt,
        String serverTraceId,
        String apiVersion) {
}
