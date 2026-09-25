package com.github.h3yow.versionedapiidempotencygatewayjava.common;

/**
 * 统一错误响应体：错误类型、可解释消息、请求标识。
 */
public record ErrorResponse(String code, String message, String requestId) {

    public static ErrorResponse of(ErrorCode errorCode, String message, String requestId) {
        return new ErrorResponse(errorCode.name(), message, requestId);
    }
}
