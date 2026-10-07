package com.github.h3yow.versionedapiidempotencygatewayjava.order.view;

import java.util.List;
import java.util.Map;

/**
 * 当前主版本（2.x/3.x）的扩展返回结构：在核心字段之外携带扩展信息。
 */
public record ExtendedOrderView(
        String orderId,
        String product,
        int quantity,
        String status,
        String viewProfile,
        String apiVersion,
        List<String> capabilities,
        Map<String, String> links) {
}
