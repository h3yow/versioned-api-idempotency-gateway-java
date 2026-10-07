package com.github.h3yow.versionedapiidempotencygatewayjava.tests.version;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.versionedapiidempotencygatewayjava.audit.AuditLogger;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyStore;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.OrderService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 版本差异化返回测试：不同主版本返回形态确实不同；同主版本未登记次版本落到兼容路径。
 */
@SpringBootTest
@AutoConfigureMockMvc
class VersionShapeTest {

    private static final Logger log = LoggerFactory.getLogger(VersionShapeTest.class);

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

    private JsonNode createAt(String declared, String key, String client) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", declared)
                        .header("X-Client-Id", client)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("widget", 2)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String selected = result.getResponse().getHeader("X-Api-Version-Selected");
        log.info("[test-version-shape] 场景 declared={} selected={} key={} 响应体={}",
                declared, selected, key, body);
        return body;
    }

    @Test
    @DisplayName("1.2（旧主版本）返回精简结构：仅 4 个核心字段，无扩展信息")
    void v1ReturnsCompactShape() throws Exception {
        JsonNode body = createAt("1.2", "key-shape-v12", "client-legacy-1");
        org.junit.jupiter.api.Assertions.assertEquals("ORD-00000001", body.path("orderId").asText());
        org.junit.jupiter.api.Assertions.assertEquals("widget", body.path("product").asText());
        org.junit.jupiter.api.Assertions.assertEquals(2, body.path("quantity").asInt());
        org.junit.jupiter.api.Assertions.assertEquals("CREATED", body.path("status").asText());
        org.junit.jupiter.api.Assertions.assertFalse(body.has("amount"), "v1 精简结构不应包含 amount");
        org.junit.jupiter.api.Assertions.assertFalse(body.has("createdAt"), "v1 精简结构不应包含 createdAt");
        org.junit.jupiter.api.Assertions.assertFalse(body.has("serverTraceId"), "v1 精简结构不应包含 serverTraceId");
        org.junit.jupiter.api.Assertions.assertFalse(body.has("apiVersion"), "v1 精简结构不应包含 apiVersion");
        org.junit.jupiter.api.Assertions.assertFalse(body.has("features"), "v1 精简结构不应包含 features");
        log.info("[test-version-shape] 用例 v1 精简字段数={}（期望 4）", body.size());
        org.junit.jupiter.api.Assertions.assertEquals(4, body.size());
    }

    @Test
    @DisplayName("2.0（当前主版本）返回扩展结构：含金额/时间/追踪/版本，且无 v3 的 features")
    void v2ReturnsExtendedShape() throws Exception {
        JsonNode body = createAt("2.0", "key-shape-v20", "client-mobile-1");
        org.junit.jupiter.api.Assertions.assertEquals("19.99", body.path("amount").asText());
        org.junit.jupiter.api.Assertions.assertTrue(body.has("createdAt"), "v2 必须返回创建时间");
        org.junit.jupiter.api.Assertions.assertEquals("ORD-00000001", body.path("serverTraceId").asText());
        org.junit.jupiter.api.Assertions.assertEquals("2.0", body.path("apiVersion").asText());
        org.junit.jupiter.api.Assertions.assertFalse(body.has("features"), "v2 不应包含 v3 的 features");
        log.info("[test-version-shape] 用例 v2 扩展字段数={}（期望 8）", body.size());
        org.junit.jupiter.api.Assertions.assertEquals(8, body.size());
    }

    @Test
    @DisplayName("3.0（更新主版本）返回进一步扩展结构：在 v2 基础上多 features")
    void v3ReturnsFurtherExtendedShape() throws Exception {
        JsonNode body = createAt("3.0", "key-shape-v30", "client-mobile-1");
        org.junit.jupiter.api.Assertions.assertEquals("3.0", body.path("apiVersion").asText());
        org.junit.jupiter.api.Assertions.assertTrue(body.has("amount"));
        org.junit.jupiter.api.Assertions.assertTrue(body.has("createdAt"));
        org.junit.jupiter.api.Assertions.assertTrue(body.path("features").isArray(),
                "v3 必须返回 features 数组");
        org.junit.jupiter.api.Assertions.assertTrue(body.path("features").size() > 0);
        log.info("[test-version-shape] 用例 v3 features={}", body.path("features"));
        org.junit.jupiter.api.Assertions.assertEquals(9, body.size());
    }

    @Test
    @DisplayName("同主版本未登记的次版本 1.1 / 2.5：不拒绝，落到该主版本兼容路径并返回对应形态")
    void unregisteredMinorFallsBackWithinMajor() throws Exception {
        // 1.1 未登记 -> 同主版本回落到 1.2（精简结构）
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "1.1")
                        .header("X-Client-Id", "client-legacy-1")
                        .header("Idempotency-Key", "key-shape-11")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("gadget", 1)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Api-Version-Selected", "1.2"))
                .andExpect(header().doesNotExist("Deprecation"));
        JsonNode v11 = objectMapper.readTree(mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "1.1")
                        .header("X-Client-Id", "client-legacy-1")
                        .header("Idempotency-Key", "key-shape-11")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("gadget", 1)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(4, v11.size(), "1.1 落在 1.x 必须仍是精简结构");
        log.info("[test-version-shape] 用例 1.1 -> 1.2 精简字段数={}（REPLAYED 同形态）", v11.size());

        // 2.5 未登记且高于已注册 -> 回落到主版本 2 的最高可服务版本 2.0（扩展结构），不拒绝
        MvcResult r25 = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.5")
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", "key-shape-25")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("widget", 1)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Api-Version-Selected", "2.0"))
                .andReturn();
        JsonNode v25 = objectMapper.readTree(r25.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(v25.has("amount"), "2.5 落在 2.x 必须仍是扩展结构");
        org.junit.jupiter.api.Assertions.assertFalse(v25.has("features"));
        log.info("[test-version-shape] 用例 2.5 -> 2.0 扩展字段数={}", v25.size());
    }

    @Test
    @DisplayName("1.0 已弃用但仍服务：精简结构 + Deprecation/Sunset 提示，且形态不受影响")
    void deprecatedMajorStillCompactAndSignaled() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "1.0")
                        .header("X-Client-Id", "client-legacy-1")
                        .header("Idempotency-Key", "key-shape-10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("gadget", 1)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Api-Version-Selected", "1.0"))
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().exists("Sunset"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(4, body.size(), "弃用的 1.0 仍是精简结构");
        log.info("[test-version-shape] 用例 1.0 弃用提示保留，精简字段数={} Sunset={}",
                body.size(), result.getResponse().getHeader("Sunset"));
    }
}
