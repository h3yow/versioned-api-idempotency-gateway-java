package com.github.h3yow.versionedapiidempotencygatewayjava.order.dto;

/**
 * 创建订单响应体。
 */
public record CreateOrderResponse(String orderId, String product, int quantity, String status) {
}
