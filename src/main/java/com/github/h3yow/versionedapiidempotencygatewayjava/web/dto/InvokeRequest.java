package com.github.h3yow.versionedapiidempotencygatewayjava.web.dto;

import java.util.Map;

/**
 * 内部统一调用入口的请求信封：能力、操作与参数一起提交。
 */
public record InvokeRequest(
        String capability,
        String operation,
        Map<String, Object> params) {
}
