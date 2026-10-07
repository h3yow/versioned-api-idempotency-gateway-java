package com.github.h3yow.versionedapiidempotencygatewayjava.order.view;

/**
 * 主版本 1.x 的精简下单返回结构：仅核心字段。
 */
public record OrderViewV1(String orderId, String product, int quantity, String status) {
}
