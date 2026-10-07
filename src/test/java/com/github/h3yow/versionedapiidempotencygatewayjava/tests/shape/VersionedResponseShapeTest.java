package com.github.h3yow.versionedapiidempotencygatewayjava.tests.shape;

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
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 版本差异化返回测试：不同主版本走出不同行为，返回结构客户端可直接区分；
 * 同主版本未登记次版本落到主版本内兼容处理；三类拒绝稳定可区分；弃用版本照常服务。
 */
@SpringBootTest
@AutoConfigureMockMvc
class VersionedResponseShapeTest {

    private static final Logger log = LoggerFactory.getLogger(VersionedResponseShapeTest.class);

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

    private String orderJson(String product) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "product", product, "quantity", 2, "amount", new BigDecimal("9.99")));
    }

    private MvcResult createOrder(String version, String key) throws Exception {
        return mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", version)
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("widget-" + key)))
                .andReturn();
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static TreeSet<String> fieldNames(JsonNode body) {
        TreeSet<String> names = new TreeSet<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void logShape(String scenario, MvcResult result, JsonNode body) {
        log.info("[test-shape] scenario={} status={} selected={} fields={} body={}",
                scenario, result.getResponse().getStatus(),
                result.getResponse().getHeader("X-Api-Version-Selected"),
                fieldNames(body), body);
    }

    @Test
    @DisplayName("旧主版本 1.2 返回精简结构：仅 orderId/status，无扩展字段")
    void oldMajorReturnsCompactShape() throws Exception {
        MvcResult result = createOrder("1.2", "shape-v12");
        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = bodyOf(result);
        logShape("1.2-compact", result, body);
        assertEquals(new TreeSet<>(java.util.Set.of("orderId", "status")),
                fieldNames(body),
                "旧主版本必须只返回精简字段");
        assertTrue(body.path("orderId").asText().startsWith("ORD-"));
        assertEquals("CREATED", body.path("status").asText());
    }

    @Test
    @DisplayName("当前主版本 2.0 / 3.0 返回扩展结构，且两个主版本之间也可区分")
    void currentMajorsReturnExtendedShape() throws Exception {
        MvcResult v2 = createOrder("2.0", "shape-v20");
        MvcResult v3 = createOrder("3.0", "shape-v30");
        JsonNode body2 = bodyOf(v2);
        JsonNode body3 = bodyOf(v3);
        logShape("2.0-extended", v2, body2);
        logShape("3.0-extended", v3, body3);

        for (JsonNode body : new JsonNode[]{body2, body3}) {
            assertTrue(body.has("orderId") && body.has("status"), "扩展结构保留核心字段");
            assertTrue(body.has("product") && body.has("quantity"), "扩展结构携带业务明细");
            assertTrue(body.has("viewProfile") && body.has("apiVersion")
                    && body.has("capabilities") && body.has("links"), "扩展结构携带扩展信息");
            assertTrue(body.path("links").path("self").asText()
                    .startsWith("/api/orders/ORD-"), "链接应指向订单资源");
        }
        assertEquals("extended-v2", body2.path("viewProfile").asText());
        assertEquals("extended-v3", body3.path("viewProfile").asText());
        assertEquals("2.0", body2.path("apiVersion").asText());
        assertEquals("3.0", body3.path("apiVersion").asText());
        assertFalse(body2.path("capabilities").toString().contains("partial-refund"),
                "2.x 不暴露 3.x 的能力");
        assertTrue(body3.path("capabilities").toString().contains("partial-refund"),
                "3.x 暴露部分退款能力，与 2.x 可区分");
    }

    @Test
    @DisplayName("同主版本未登记次版本 1.5：落到 1.2 的兼容处理，返回精简结构而非拒绝")
    void unregisteredMinorFallsBackWithinMajor() throws Exception {
        MvcResult result = createOrder("1.5", "shape-v15");
        assertEquals(200, result.getResponse().getStatus(), "未登记次版本不得直接拒绝");
        assertEquals("1.2", result.getResponse().getHeader("X-Api-Version-Selected"));
        JsonNode body = bodyOf(result);
        logShape("1.5-fallback", result, body);
        assertEquals(new TreeSet<>(java.util.Set.of("orderId", "status")),
                fieldNames(body),
                "落到 1.x 主版本内兼容处理，返回该主版本的精简结构");
    }

    @Test
    @DisplayName("同主版本未登记次版本 2.9（高于所有注册次版本）：落到 2.0 的兼容处理，返回扩展结构")
    void unregisteredHigherMinorFallsBackWithinMajor() throws Exception {
        MvcResult result = createOrder("2.9", "shape-v29");
        assertEquals(200, result.getResponse().getStatus(), "高于注册值的次版本同样不得直接拒绝");
        assertEquals("2.0", result.getResponse().getHeader("X-Api-Version-Selected"));
        JsonNode body = bodyOf(result);
        logShape("2.9-fallback", result, body);
        assertEquals("extended-v2", body.path("viewProfile").asText());
    }

    @Test
    @DisplayName("已弃用版本 1.0 照常服务：精简结构 + Deprecation/Sunset 提示")
    void deprecatedVersionStillServedWithCompactShape() throws Exception {
        MvcResult result = createOrder("1.0", "shape-v10");
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("true", result.getResponse().getHeader("Deprecation"));
        assertTrue(result.getResponse().getHeader("Sunset") != null
                && result.getResponse().getHeader("Sunset").contains("2027-06-30"));
        JsonNode body = bodyOf(result);
        logShape("1.0-deprecated", result, body);
        assertEquals(new TreeSet<>(java.util.Set.of("orderId", "status")),
                fieldNames(body));
    }

    @Test
    @DisplayName("三类拒绝保持稳定可区分：跨主版本不兼容 / 缺版本 / 版本无法识别")
    void rejectionsRemainStableAndDistinct() throws Exception {
        MvcResult incompatible = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "0.1")
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", "shape-rej-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("widget")))
                .andExpect(status().isBadRequest()).andReturn();
        JsonNode incompatibleBody = bodyOf(incompatible);
        assertEquals("VERSION_INCOMPATIBLE", incompatibleBody.path("code").asText());

        MvcResult missing = mockMvc.perform(post("/api/orders")
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", "shape-rej-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("widget")))
                .andExpect(status().isBadRequest()).andReturn();
        assertEquals("VERSION_MISSING", bodyOf(missing).path("code").asText());

        MvcResult unknownMajor = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "9.0")
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", "shape-rej-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("widget")))
                .andExpect(status().isNotFound()).andReturn();
        assertEquals("VERSION_UNKNOWN", bodyOf(unknownMajor).path("code").asText());

        MvcResult malformed = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "v2")
                        .header("X-Client-Id", "client-mobile-1")
                        .header("Idempotency-Key", "shape-rej-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("widget")))
                .andExpect(status().isNotFound()).andReturn();
        assertEquals("VERSION_UNKNOWN", bodyOf(malformed).path("code").asText());

        log.info("[test-shape] 拒绝矩阵 incompatible={} missing={} unknownMajor={} malformed={}",
                incompatibleBody.path("code").asText(),
                bodyOf(missing).path("code").asText(),
                bodyOf(unknownMajor).path("code").asText(),
                bodyOf(malformed).path("code").asText());
        assertEquals(0, orderService.sideEffectCount(), "拒绝请求不得产生副作用");
    }

    @Test
    @DisplayName("查询接口同样按版本塑形：1.2 精简、2.0 扩展")
    void getOrderAlsoShapedByVersion() throws Exception {
        MvcResult created = createOrder("2.0", "shape-get");
        String orderId = bodyOf(created).path("orderId").asText();

        MvcResult gotV1 = mockMvc.perform(org.springframework.test.web.servlet
                        .request.MockMvcRequestBuilders.get("/api/orders/" + orderId)
                        .header("X-Api-Version", "1.2")
                        .header("X-Client-Id", "client-mobile-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Api-Version-Selected", "1.2"))
                .andReturn();
        JsonNode compact = bodyOf(gotV1);
        logShape("get-1.2", gotV1, compact);
        assertEquals(new TreeSet<>(java.util.Set.of("orderId", "status")),
                fieldNames(compact));

        MvcResult gotV2 = mockMvc.perform(org.springframework.test.web.servlet
                        .request.MockMvcRequestBuilders.get("/api/orders/" + orderId)
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-mobile-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Api-Version-Selected", "2.0"))
                .andReturn();
        JsonNode extended = bodyOf(gotV2);
        logShape("get-2.0", gotV2, extended);
        assertEquals("extended-v2", extended.path("viewProfile").asText());
        assertEquals(orderId, extended.path("orderId").asText());
    }
}
