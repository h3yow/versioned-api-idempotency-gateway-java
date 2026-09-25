package com.github.h3yow.versionedapiidempotencygatewayjava.web;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiException;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ErrorCode;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ErrorResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常映射：鉴权失败、版本不兼容、幂等冲突与业务异常 -> 稳定错误契约。
 *
 * <p>所有错误体固定为 {@code {code, message, requestId}}，并回写 X-Request-Id 头；
 * 不向调用方泄露堆栈与内部类名。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return build(ex.errorCode(), ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return build(ErrorCode.BUSINESS_ERROR, "请求体格式不正确或无法解析");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        return build(ErrorCode.INTERNAL, "网关内部处理异常");
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message) {
        String requestId = MDC.get("requestId");
        ErrorResponse body = ErrorResponse.of(code, message, requestId);
        return ResponseEntity.status(HttpStatus.valueOf(code.httpStatus()))
                .header("X-Request-Id", requestId == null ? "unknown" : requestId)
                .body(body);
    }
}
