package com.github.h3yow.versionedapiidempotencygatewayjava.tests.auth;

import com.github.h3yow.versionedapiidempotencygatewayjava.audit.AuditEntry;
import com.github.h3yow.versionedapiidempotencygatewayjava.audit.AuditLogger;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiException;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ErrorCode;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.RequestContext;
import com.github.h3yow.versionedapiidempotencygatewayjava.gateway.GatewayService;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyStore;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.OrderService;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderRequest;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限测试：未认证、越权（不泄露资源存在性），并打印权限判定依据与请求标识。
 */
@SpringBootTest
class AuthorizationTest {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationTest.class);

    @Autowired
    private GatewayService gatewayService;
    @Autowired
    private AuditLogger auditLogger;
    @Autowired
    private OrderService orderService;
    @Autowired
    private IdempotencyStore idempotencyStore;

    @BeforeEach
    void resetState() {
        auditLogger.clear();
        orderService.reset();
        idempotencyStore.clear();
    }

    private ApiException expectRejected(RequestContext ctx, CreateOrderRequest request,
                                        ErrorCode expected) {
        ApiException ex = assertThrows(ApiException.class,
                () -> gatewayService.createOrder(ctx, request));
        assertEquals(expected, ex.errorCode());
        AuditEntry entry = latestAudit();
        log.info("[test-auth] requestId={} clientId={} 判定依据 auth='{}' 结果 result={} -> {}",
                ctx.requestId(), ctx.clientId(), entry.authDecision(), entry.result(),
                ex.errorCode().name());
        return ex;
    }

    private AuditEntry latestAudit() {
        var entries = auditLogger.entries();
        return entries.get(entries.size() - 1);
    }

    private CreateOrderRequest sampleRequest() {
        return new CreateOrderRequest("widget", 2, new BigDecimal("19.99"));
    }

    @Test
    @DisplayName("缺少客户端标识 -> AUTH_UNAUTHENTICATED(401)")
    void missingClientIsUnauthenticated() {
        RequestContext ctx = new RequestContext("req-auth-1", "2.0", null, "k-1");
        expectRejected(ctx, sampleRequest(), ErrorCode.AUTH_UNAUTHENTICATED);
        assertEquals(0, orderService.sideEffectCount());
    }

    @Test
    @DisplayName("未登记客户端 -> AUTH_UNAUTHENTICATED(401)，错误信息不区分其具体资源")
    void unknownClientIsUnauthenticated() {
        RequestContext ctx = new RequestContext("req-auth-2", "2.0", "client-ghost", "k-2");
        ApiException ex = expectRejected(ctx, sampleRequest(), ErrorCode.AUTH_UNAUTHENTICATED);
        assertEquals(401, ex.errorCode().httpStatus());
        assertEquals(0, orderService.sideEffectCount());
    }

    @Test
    @DisplayName("只读客户端写订单 -> AUTH_FORBIDDEN(403)，无副作用")
    void readOnlyClientCannotWrite() {
        RequestContext ctx = new RequestContext("req-auth-3", "2.0", "client-readonly-1", "k-3");
        ApiException ex = expectRejected(ctx, sampleRequest(), ErrorCode.AUTH_FORBIDDEN);
        assertEquals(403, ex.errorCode().httpStatus());
        assertTrue(latestAudit().authDecision().contains("orders:write"));
        assertEquals(0, orderService.sideEffectCount());
    }

    @Test
    @DisplayName("越权查询不存在/存在的资源都返回空（404 同构），不泄露资源是否存在")
    void unauthorizedReadHidesExistence() throws Exception {
        // 先用有权限客户端创建一个真实订单
        RequestContext owner = new RequestContext("req-auth-owner", "2.0",
                "client-mobile-1", "k-owner");
        CreateOrderResponse created = gatewayService.createOrder(owner, sampleRequest()).body();
        log.info("[test-auth] 已创建真实订单 orderId={}", created.orderId());

        RequestContext denied = new RequestContext("req-auth-4", "2.0",
                "client-noaccess-1", null);

        // 查询“存在”的资源与“不存在”的资源，对外表现必须完全一致
        boolean existingVisible = gatewayService.getOrder(denied, created.orderId()).isPresent();
        boolean missingVisible = gatewayService.getOrder(denied, "ORD-99999999").isPresent();

        log.info("[test-auth] requestId={} 越权访问存在资源 -> present={}；不存在资源 -> present={}",
                denied.requestId(), existingVisible, missingVisible);
        assertTrue(!existingVisible, "越权访问存在资源必须表现为不存在");
        assertTrue(!missingVisible, "真正不存在的资源同样为空，两者对外不可区分");

        // 审计中必须能还原真实判定依据
        AuditEntry entry = auditLogger.entries().stream()
                .filter(e -> ("DENIED_AS_NOT_FOUND:" + created.orderId()).equals(e.result()))
                .findFirst().orElseThrow();
        log.info("[test-auth] 审计保留真实判定: requestId={} authDecision='{}' result={}",
                entry.requestId(), entry.authDecision(), entry.result());
        assertTrue(entry.authDecision().contains("orders:read"));
    }

    @Test
    @DisplayName("只读客户端读资源允许；认证失败先于版本/幂等判定")
    void readScopeAllowsReadAndAuthTakesPrecedence() {
        // 错误版本 + 未认证：必须先报 401，而非版本错误
        RequestContext ctx = new RequestContext("req-auth-5", "9.9", null, "k-5");
        expectRejected(ctx, sampleRequest(), ErrorCode.AUTH_UNAUTHENTICATED);
    }
}
