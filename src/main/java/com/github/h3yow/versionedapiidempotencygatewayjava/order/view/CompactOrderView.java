package com.github.h3yow.versionedapiidempotencygatewayjava.order.view;

/**
 * 旧主版本（1.x）的精简返回结构：只保留核心字段。
 */
public record CompactOrderView(String orderId, String status) {
}
