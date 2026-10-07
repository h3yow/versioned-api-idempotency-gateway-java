package com.github.h3yow.versionedapiidempotencygatewayjava.order.dto;

import java.math.BigDecimal;

/**
 * 下单能力的内部领域结果：与对外版本无关的完整订单信息。
 *
 * <p>幂等缓存与两个调用入口都以本类型为准；最终返回给客户端的形态
 * （v1 精简 / v2、v3 扩展）由 OrderResponsePresenter 按协商选中的主版本渲染。
 *
 * @param orderId   订单号
 * @param product   商品
 * @param quantity  数量
 * @param status    订单状态
 * @param amount    金额（可能为空，例如历史读取路径）
 * @param createdAt 创建时间戳（ISO 字符串，创建时有值）
 */
public record CreateOrderResult(
        String orderId,
        String product,
        int quantity,
        String status,
        BigDecimal amount,
        String createdAt) {
}
