package com.github.h3yow.versionedapiidempotencygatewayjava.tests.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.versionedapiidempotencygatewayjava.audit.AuditLogger;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyStore;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.OrderService;
import com.github.h3yow.versionedapiidempotencygatewayjava.web.GlobalExceptionHandler;
import com.github.h3yow.versionedapiidempotencygatewayjava.web.OrderController;
import com.github.h3yow.versionedapiidempotencygatewayjava.web.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 契约测试：错误类型可区分、响应契约稳定、弃用/幂等信号与审计日志可解释。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayContractTest {

    private static final Logger log = LoggerFactory.getLogger(GatewayContractTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
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

    private String orderJson(String product, int quantity) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "product", product, "quantity", quantity,
                "amount", new BigDecimal("19.99")));
    }

    private JsonNode expectError(MvcResult result, String expectedCode, int expectedStatus)
            throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        log.info("[test-contract] status={} code={} message='{}' requestId={}",
                result.getResponse().getStatus(),
                body.path("code").asText(), body.path("message").asText(),
                body.path("requestId").asText());
        org.junit.jupiter.api.Assertions.assertEquals(expectedStatus,
                result.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(expectedCode, body.path("code").asText());
        org.junit.jupiter.api.Assertions.assertTrue(
                body.path("message").asText().length() > 0);
        org.junit.jupiter.api.Assertions.assertFalse(body.has("stackTrace"));
        return body;
    }

    @Test
    @DisplayName("错误契约字段固定为 code/message/requestId，且错误类型两两可区分")
    void errorContractIsStableAndDistinguishable() throws Exception {
        MvcResult missingVersion = mockMvc.perform(post("/api/orders")
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", "k-missing-version")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("widget", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Request-Id"))
                .andReturn();
        expectError(missingVersion, "VERSION_MISSING", 400);

        MvcResult unknown = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "9.0")
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", "k-unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("widget", 1)))
                .andExpect(status().isNotFound())
                .andReturn();
        expectError(unknown, "VERSION_UNKNOWN", 404);

        MvcResult incompatible = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "0.1")
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", "k-incompat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("widget", 1)))
                .andExpect(status().isBadRequest())
                .andReturn();
        expectError(incompatible, "VERSION_INCOMPATIBLE", 400);

        MvcResult unauth = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("Idempotency-Key", "k-unauth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("widget", 1)))
                .andExpect(status().isUnauthorized())
                .andReturn();
        expectError(unauth, "AUTH_UNAUTHENTICATED", 401);

        MvcResult forbidden = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-readonly-1")
                        .header("Idempotency-Key", "k-forbidden")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("widget", 1)))
                .andExpect(status().isForbidden())
                .andReturn();
        expectError(forbidden, "AUTH_FORBIDDEN", 403);
    }

    @Test
    @DisplayName("成功创建返回 200，并回写选中版本与 EXECUTED 幂等结论")
    void successCarriesDecisionHeaders() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", "k-success-1")
                        .header("X-Request-Id", "trace-success-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("widget", 1)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Api-Version-Selected", "2.0"))
                .andExpect(header().string("X-Idempotency-Outcome", "EXECUTED"))
                .andExpect(header().string("X-Request-Id", "trace-success-1"))
                .andExpect(header().doesNotExist("Deprecation"));
        org.junit.jupiter.api.Assertions.assertEquals(1, orderService.sideEffectCount());
    }

    @Test
    @DisplayName("已弃用版本 1.0 返回 Deprecation/Sunset 信号但调用成功")
    void deprecatedVersionEmitsDeprecationHeaders() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "1.0")
                        .header("X-Client-Id", "client-legacy-1")
                        .header("Idempotency-Key", "k-deprecated-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("gadget", 1)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Api-Version-Selected", "1.0"))
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().string("X-Idempotency-Outcome", "EXECUTED"));
    }

    @Test
    @DisplayName("同键串行重复提交：第二次 REPLAYED 且响应体与首次一致，副作用仅一次")
    void replayOverHttpReturnsIdenticalBody() throws Exception {
        String body = orderJson("widget", 3);
        MvcResult first = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", "k-replay-http")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotency-Outcome", "EXECUTED"))
                .andReturn();
        MvcResult second = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", "k-replay-http")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Idempotency-Outcome", "REPLAYED"))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(
                first.getResponse().getContentAsString(),
                second.getResponse().getContentAsString(), "重复提交必须返回与首次一致的结果");
        org.junit.jupiter.api.Assertions.assertEquals(1, orderService.sideEffectCount());
    }

    @Test
    @DisplayName("键冲突 -> 409 IDEMPOTENCY_KEY_CONFLICT；进行中重试 -> 409 IDEMPOTENCY_IN_PROGRESS")
    void conflictAndInProgressOverHttp() throws Exception {
        // 先成功一次
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", "k-shared")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("widget", 1)))
                .andExpect(status().isOk());
        // 同键不同负载
        MvcResult conflict = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", "k-shared")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("gadget", 5)))
                .andExpect(status().isConflict())
                .andReturn();
        expectError(conflict, "IDEMPOTENCY_KEY_CONFLICT", 409);
    }

    @Test
    @DisplayName("业务异常映射为 422 BUSINESS_ERROR，错误契约一致")
    void businessErrorMapped() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", "k-biz-fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("forbidden-product", 1)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        expectError(result, "BUSINESS_ERROR", 422);
        org.junit.jupiter.api.Assertions.assertEquals(0, orderService.sideEffectCount());
    }

    @Test
    @DisplayName("未授权查询资源与资源不存在同为 404，不泄露存在性")
    void unauthorizedAndMissingBoth404() throws Exception {
        MvcResult denied = mockMvc.perform(get("/api/orders/ORD-00000001")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-noaccess-1"))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult missing = mockMvc.perform(get("/api/orders/ORD-99999999")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-mobile-1"))
                .andExpect(status().isNotFound())
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(404, denied.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(404, missing.getResponse().getStatus());
        log.info("[test-contract] 越权与不存在均为 404，响应体均无业务内容");
    }

    @Test
    @DisplayName("审计日志记录请求标识、版本决策、幂等结果、权限判定依据")
    void auditTrailCapturesAllDecisions() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "1.2")
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", "k-audit-1")
                        .header("X-Request-Id", "trace-audit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("widget", 1)))
                .andExpect(status().isOk());
        var entry = auditLogger.entries().stream()
                .filter(e -> "trace-audit-1".equals(e.requestId()))
                .findFirst().orElseThrow();
        log.info("[test-contract] 审计 requestId={} versionDecision='{}' "
                        + "idempotencyOutcome={} authDecision='{}' result={}",
                entry.requestId(), entry.versionDecision(), entry.idempotencyOutcome(),
                entry.authDecision(), entry.result());
        org.junit.jupiter.api.Assertions.assertTrue(entry.versionDecision().contains("1.2"));
        org.junit.jupiter.api.Assertions.assertEquals("EXECUTED", entry.idempotencyOutcome());
        org.junit.jupiter.api.Assertions.assertTrue(entry.authDecision().contains("orders:write"));
        org.junit.jupiter.api.Assertions.assertTrue(entry.result().startsWith("SUCCESS:"));
    }
}
