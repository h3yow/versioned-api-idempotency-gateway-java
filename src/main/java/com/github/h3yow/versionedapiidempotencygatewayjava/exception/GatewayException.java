package com.github.h3yow.versionedapiidempotencygatewayjava.exception;

import java.util.Map;

/** 网关统一异常，携带稳定错误类型、明细与请求标识（由门面在抛出时写入）。 */
public class GatewayException extends RuntimeException {

    private final ErrorType errorType;
    private final Map<String, Object> details;
    private String requestId;

    public GatewayException(ErrorType errorType, String message) {
        this(errorType, message, Map.of(), null);
    }

    public GatewayException(ErrorType errorType, String message, Map<String, Object> details) {
        this(errorType, message, details, null);
    }

    public GatewayException(ErrorType errorType, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.details = details == null ? Map.of() : details;
    }

    public ErrorType errorType() {
        return errorType;
    }

    public Map<String, Object> details() {
        return details;
    }

    public String requestId() {
        return requestId;
    }

    /** 写入请求标识并返回自身，便于门面在统一出口关联审计与错误契约。 */
    public GatewayException withRequestId(String requestId) {
        this.requestId = requestId;
        return this;
    }
}
