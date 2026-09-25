package com.github.h3yow.versionedapiidempotencygatewayjava.order.dto;

import java.math.BigDecimal;

/**
 * 创建订单请求体。
 */
public record CreateOrderRequest(String product, int quantity, BigDecimal amount) {

    /**
     * 请求负载指纹：规范化字段后做稳定哈希，用于检测幂等键冲突。
     */
    public String fingerprint() {
        String canonical = (product == null ? "" : product.trim())
                + "|" + quantity
                + "|" + (amount == null ? "" : amount.stripTrailingZeros().toPlainString());
        return "sha256:" + Integer.toHexString(canonical.hashCode());
    }
}
