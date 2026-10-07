package com.github.h3yow.versionedapiidempotencygatewayjava.order.view;

import java.math.BigDecimal;
import java.util.List;

/**
 * 主版本 3.x 的扩展下单返回结构：在 2.x 扩展信息之上再附能力提示。
 */
public record OrderViewV3(
        String orderId,
        String product,
        int quantity,
        String status,
        BigDecimal amount,
        String createdAt,
        String serverTraceId,
        String apiVersion,
        List<String> features) {
}
