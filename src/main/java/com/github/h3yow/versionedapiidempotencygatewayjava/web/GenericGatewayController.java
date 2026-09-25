package com.github.h3yow.versionedapiidempotencygatewayjava.web;

import com.github.h3yow.versionedapiidempotencygatewayjava.exception.ErrorType;
import com.github.h3yow.versionedapiidempotencygatewayjava.exception.GatewayException;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.ClientCapability;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.CreateOrderRequest;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.GatewayResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 第二个调用入口：POST /gateway/invoke。
 * 与 REST 入口共用同一个 {@link GatewayFacade}，因此版本协商、幂等、
 * 权限判定、错误契约、弃用信号在两个入口下完全一致。
 */
@RestController
public class GenericGatewayController {

    private final GatewayFacade facade;

    public GenericGatewayController(GatewayFacade facade) {
        this.facade = facade;
    }

    @PostMapping("/gateway/invoke")
    public ResponseEntity<GatewayResponse<Object>> invoke(
            @RequestHeader(value = "X-Api-Version", required = false) String apiVersion,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Capability", required = false) String capabilityHeader,
            @RequestBody(required = false) Map<String, Object> body) {

        ClientCapability capability;
        if (capabilityHeader == null || capabilityHeader.isBlank()) {
            throw new GatewayException(ErrorType.VALIDATION_ERROR,
                    "X-Capability header is required").withRequestId(UUID.randomUUID().toString());
        }
        try {
            capability = ClientCapability.valueOf(capabilityHeader.trim());
        } catch (IllegalArgumentException e) {
            throw new GatewayException(ErrorType.VALIDATION_ERROR,
                    "unknown capability: " + capabilityHeader).withRequestId(UUID.randomUUID().toString());
        }

        // 通用入口将 JSON Map 映射为具体能力的请求对象
        Object requestBody = convert(capability, body);

        String requestId = UUID.randomUUID().toString();
        GatewayResponse<Object> result = facade.invoke(
                apiVersion, clientId, idempotencyKey, capability, requestBody, requestId);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Request-Id", requestId);
        headers.add("X-Resolved-Version", result.resolvedVersion());
        if (result.deprecated()) {
            headers.add("Deprecation", "true");
            headers.add("Sunset", "Wed, 31 Mar 2027 00:00:00 GMT");
        }
        if (result.idempotency() != null && result.idempotency().replayed()) {
            headers.add("Idempotent-Replay", "true");
            headers.add("X-Idempotency-Status", result.idempotency().status());
        }
        return ResponseEntity.ok().headers(headers).body(result);
    }

    private Object convert(ClientCapability capability, Map<String, Object> body) {
        if (capability == ClientCapability.ORDER_CREATE) {
            if (body == null) {
                return null;
            }
            Object quantityRaw = body.get("quantity");
            Integer quantity = quantityRaw == null ? null
                    : (quantityRaw instanceof Number n ? n.intValue() : Integer.valueOf(quantityRaw.toString()));
            return new CreateOrderRequest(
                    body.get("item") == null ? null : body.get("item").toString(),
                    quantity);
        }
        return body;
    }
}
