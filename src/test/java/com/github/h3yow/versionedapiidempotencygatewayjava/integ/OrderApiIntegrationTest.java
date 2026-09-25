package com.github.h3yow.versionedapiidempotencygatewayjava.tests.integ;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.versionedapiidempotencygatewayjava.audit.AuditLogger;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.AuditEvent;
import com.github.h3yow.versionedapiidempotencygatewayjava.web.handler.OrderSideEffectCounter;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 端到端集成测试：版本协商、幂等、权限、错误契约、弃用信号、审计。 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(OrderApiIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OrderSideEffectCounter sideEffectCounter;
    @Autowired
    private AuditLogger auditLogger;

    @BeforeEach
    void resetState() {
        sideEffectCounter.reset();
    }

    private void printAudit(String label) {
        for (AuditEvent e : auditLogger.events()) {
            if (e.requestId().startsWith(label) || label.isEmpty()) {
                log.info("[审计] requestId={} client={} capability={} 版本头='{}' 版本决策={} 幂等={} 权限={} 结果={}",
                        e.requestId(), e.clientId(), e.capability(), e.rawVersion(),
                        e.versionDecision(), e.idempotencyStatus(), e.authorization(), e.outcome());
            }
        }
    }

    private String orderJson(String item, int qty) throws Exception {
        return objectMapper.writeValueAsString(
                new java.util.LinkedHashMap<>(java.util.Map.of("item", item, "quantity", qty)));
    }

    @Test
    @DisplayName("缺失版本头：400 VERSION_MISSING，错误契约稳定")
    void missingVersionRejected() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", "k-missing-version")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("book", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERSION_MISSING"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(header().exists("X-Request-Id"));
        printAudit("");
    }

    @Test
    @DisplayName("未知版本：400 VERSION_UNKNOWN，与缺失/不兼容可区分")
    void unknownVersionRejected() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "1.5")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", "k-unknown-version")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("book", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERSION_UNKNOWN"));
    }

    @Test
    @DisplayName("跨主版本不兼容：400 VERSION_INCOMPATIBLE，错误类型独立可区分")
    void incompatibleVersionRejected() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "3.0")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", "k-incompatible")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("book", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERSION_INCOMPATIBLE"));
        log.info("[版本不兼容] 断言错误契约 code=VERSION_INCOMPATIBLE, http=400");
    }

    @Test
    @DisplayName("弃用版本 1.9：调用成功且返回 Deprecation/Sunset 信号，不影响 1.0 客户端")
    void deprecatedVersionSignaledButServed() throws Exception {
        MvcResult deprecated = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "1.9")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", "k-deprecated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("phone", 2)))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().exists("Sunset"))
                .andExpect(jsonPath("$.resolvedVersion").value("1.9"))
                .andExpect(jsonPath("$.deprecated").value(true))
                .andReturn();

        // 受支持旧版本 1.0 不受影响，且无弃用信号
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "1.0")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", "k-supported-old")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("phone", 2)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Deprecation"))
                .andExpect(jsonPath("$.resolvedVersion").value("1.0"))
                .andExpect(jsonPath("$.deprecated").value(false));

        log.info("[弃用信号] 1.9 响应头 Deprecation={}, Sunset={}",
                deprecated.getResponse().getHeader("Deprecation"),
                deprecated.getResponse().getHeader("Sunset"));
        assertEquals(2, sideEffectCounter.get(), "1.9 与 1.0 各自正常产生一次副作用");
    }

    @Test
    @DisplayName("成功后同键重复提交：返回与首次一致的冻结结果，标记 replay，副作用仅一次")
    void duplicateSubmitReplaysSameResult() throws Exception {
        String body = orderJson("laptop", 1);

        MvcResult first = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", "k-dup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", "k-dup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.idempotency.status").value("REPLAY_SUCCEEDED"))
                .andExpect(jsonPath("$.idempotency.replayed").value(true))
                .andReturn();

        String firstData = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").toString();
        String secondData = objectMapper.readTree(second.getResponse().getContentAsString())
                .path("data").toString();
        assertEquals(firstData, secondData, "重复提交的业务结果必须与首次一致（冻结回放）");
        assertEquals(1, sideEffectCounter.get(), "重复提交不得产生第二次副作用");
        log.info("[幂等回放] 首次={} 重复={} 业务结果一致=true 副作用={}",
                objectMapper.readTree(first.getResponse().getContentAsString()).path("idempotency").path("status").asText(),
                objectMapper.readTree(second.getResponse().getContentAsString()).path("idempotency").path("status").asText(),
                sideEffectCounter.get());
    }

    @Test
    @DisplayName("失败后重试：首次 422 BUSINESS_ERROR，同键同体重试成功，状态 RETRY_AFTER_FAILURE")
    void retryAfterFailure() throws Exception {
        String key = "k-fail-retry";
        String body = orderJson("FAIL-ONCE", 1);

        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_ERROR"));
        assertEquals(0, sideEffectCounter.get(), "失败不产生业务副作用");

        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotency.status").value("RETRY_AFTER_FAILURE"))
                .andExpect(jsonPath("$.idempotency.replayed").value(false));
        assertEquals(1, sideEffectCounter.get(), "重试成功后副作用恰好一次");

        // 成功后再提交变为回放，结果冻结
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.idempotency.status").value("REPLAY_SUCCEEDED"));
        assertEquals(1, sideEffectCounter.get(), "回放不再产生副作用");
        printAudit("");
    }

    @Test
    @DisplayName("同键不同请求体：409 IDEMPOTENCY_KEY_CONFLICT，原结果不被覆盖")
    void keyConflict() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", "k-conflict-e2e")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("mouse", 1)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", "k-conflict-e2e")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("keyboard", 9)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    @DisplayName("未授权访问统一返回 404 RESOURCE_NOT_FOUND，不区分客户端不存在/能力不足，不泄露资源")
    void unauthorizedLooksLikeMissingResource() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-unknown")
                        .header("Idempotency-Key", "k-authz-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("book", 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("resource not found"));

        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-b")
                        .header("Idempotency-Key", "k-authz-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("book", 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("resource not found"));

        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("Idempotency-Key", "k-authz-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("book", 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        printAudit("");
    }

    @Test
    @DisplayName("版本协商选择兼容处理路径：1.x 走 v1，2.x 走 v2，响应中的 handlerVersion 可区分")
    void versionSelectsHandlerPath() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "1.0")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", "k-path-v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("watch", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(org.hamcrest.Matchers.startsWith("v1-order-")))
                .andExpect(jsonPath("$.data.handlerVersion").value("1"));

        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", "k-path-v2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("watch", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(org.hamcrest.Matchers.startsWith("v2-order-")))
                .andExpect(jsonPath("$.data.handlerVersion").value("2"));
    }
}
