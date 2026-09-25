package com.github.h3yow.versionedapiidempotencygatewayjava.web;

import com.github.h3yow.versionedapiidempotencygatewayjava.model.ClientCapability;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.CreateOrderRequest;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.GatewayResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST 调用入口：POST /api/orders。
 * 仅做协议适配，所有判定逻辑都走统一 {@link GatewayFacade}。
 */
@RestController
public class OrderController {

    static final String NEWEST_VERSION = "2.0";

    private final GatewayFacade facade;

    public OrderController(GatewayFacade facade) {
        this.facade = facade;
    }

    @PostMapping("/api/orders")
    public ResponseEntity<GatewayResponse<Object>> createOrder(
            @RequestHeader(value = "X-Api-Version", required = false) String apiVersion,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) CreateOrderRequest request) {

        String requestId = newRequestId();
        GatewayResponse<Object> body = facade.invoke(
                apiVersion, clientId, idempotencyKey,
                ClientCapability.ORDER_CREATE, request, requestId);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Request-Id", requestId);
        headers.add("X-Resolved-Version", body.resolvedVersion());
        if (body.deprecated()) {
            headers.add("Deprecation", "true");
            headers.add("Sunset", "Wed, 31 Mar 2027 00:00:00 GMT");
            headers.add("Link", "</api/orders>; rel=\"successor-version\"; version=\""
                    + NEWEST_VERSION + "\"");
        }
        if (body.idempotency() != null && body.idempotency().replayed()) {
            headers.add("Idempotent-Replay", "true");
            headers.add("X-Idempotency-Status", body.idempotency().status());
        }
        return ResponseEntity.ok().headers(headers).body(body);
    }

    static String newRequestId() {
        return UUID.randomUUID().toString();
    }
}
