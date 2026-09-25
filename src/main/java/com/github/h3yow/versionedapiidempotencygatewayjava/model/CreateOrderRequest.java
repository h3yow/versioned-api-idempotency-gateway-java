package com.github.h3yow.versionedapiidempotencygatewayjava.model;

/** 创建订单请求体。 */
public record CreateOrderRequest(String item, Integer quantity) {
}
