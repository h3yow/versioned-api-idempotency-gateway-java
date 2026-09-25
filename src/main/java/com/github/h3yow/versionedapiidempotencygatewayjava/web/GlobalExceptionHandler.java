package com.github.h3yow.versionedapiidempotencygatewayjava.web;

import com.github.h3yow.versionedapiidempotencygatewayjava.exception.ErrorType;
import com.github.h3yow.versionedapiidempotencygatewayjava.exception.GatewayException;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.ErrorContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 将各类异常映射为稳定的错误契约 {@link ErrorContract}：
 * <ul>
 *   <li>每种 {@link ErrorType} 对应固定 code 与 HTTP 状态</li>
 *   <li>鉴权拒绝统一映射为 404 RESOURCE_NOT_FOUND（不泄露资源是否存在）</li>
 *   <li>未知异常统一为 500 INTERNAL_ERROR，不回传堆栈/内部细节</li>
 *   <li>每个错误都带 X-Request-Id 响应头，与审计记录对应</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorContract> handleGateway(GatewayException ex) {
        ErrorType type = ex.errorType();
        String requestId = ex.requestId() != null ? ex.requestId() : UUID.randomUUID().toString();
        ErrorContract contract = new ErrorContract(
                requestId,
                type.code(),
                ex.getMessage(),
                ex.details(),
                Instant.now());
        return ResponseEntity.status(type.httpStatus())
                .header("X-Request-Id", contract.requestId())
                .body(contract);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorContract> handleUnreadable(HttpMessageNotReadableException ex) {
        return error(ErrorType.VALIDATION_ERROR, "malformed JSON request body", Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorContract> handleOther(Exception ex) {
        log.error("unhandled exception", ex);
        return error(ErrorType.INTERNAL_ERROR, "internal error", Map.of());
    }

    private static ResponseEntity<ErrorContract> error(ErrorType type, String message,
                                                       Map<String, Object> details) {
        ErrorContract contract = new ErrorContract(
                UUID.randomUUID().toString(), type.code(), message, details, Instant.now());
        return ResponseEntity.status(HttpStatus.valueOf(type.httpStatus()))
                .header("X-Request-Id", contract.requestId())
                .body(contract);
    }
}
