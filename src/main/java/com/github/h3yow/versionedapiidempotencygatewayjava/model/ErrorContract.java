package com.github.h3yow.versionedapiidempotencygatewayjava.model;

import java.time.Instant;
import java.util.Map;

/** 稳定的错误响应契约。 */
public record ErrorContract(
        String requestId,
        String code,
        String message,
        Map<String, Object> details,
        Instant timestamp
) {
}
