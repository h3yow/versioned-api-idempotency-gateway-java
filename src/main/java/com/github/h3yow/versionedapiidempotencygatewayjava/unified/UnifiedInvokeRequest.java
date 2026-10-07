package com.github.h3yow.versionedapiidempotencygatewayjava.unified;

import java.util.Map;

/**
 * 统一调用入口请求：能力 + 操作 + 参数一次提交。
 *
 * @param capability 业务能力（如 orders）
 * @param operation  能力内操作（如 createOrder / getOrder）
 * @param params     操作参数（键值集合，由目标操作负责解释）
 */
public record UnifiedInvokeRequest(String capability, String operation, Map<String, Object> params) {
}
