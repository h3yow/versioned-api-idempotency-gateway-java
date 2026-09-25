package com.github.h3yow.versionedapiidempotencygatewayjava.model;

import java.time.Instant;

/** 创建订单响应体。 */
public record CreateOrderResponse(
        String orderId,
        String item,
        int quantity,
        String handlerVersion,
        Instant createdAt
) {
}
