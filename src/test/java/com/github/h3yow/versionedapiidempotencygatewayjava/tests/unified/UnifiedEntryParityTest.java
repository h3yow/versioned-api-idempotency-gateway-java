package com.github.h3yow.versionedapiidempotencygatewayjava.tests.unified;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一入口与业务端点一致性测试：用真实 HTTP（随机端口）与真实并发请求验证。
 *
 * <p>覆盖：正常返回（含版本形态、选中版本、弃用提示、幂等结论）、各类拒绝
 * （未认证、版本不兼容/缺失/未知、越权、重复提交），以及并发同键下两入口结论一致；
 * 未授权时不暴露资源存在性。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnifiedEntryParityTest {

    private static final Logger log = LoggerFactory.getLogger(UnifiedEntryParityTest.class);

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private OrderService orderService;
    @Autowired
    private IdempotencyStore idempotencyStore;
    @Autowired
    private AuditLogger auditLogger;

    private String base;

    /** 归一化的一次调用结果，便于两个入口逐项对比。 */
    private record Outcome(int status, String body,
                           String selectedVersion, String deprecation, String sunset,
                           String idemOutcome, String requestId, String errorCode) {
    }

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + port;
        orderService.reset();
        idempotencyStore.clear();
        auditLogger.clear();
    }

    // ------------------------------------------------------------------
    // 调用封装：业务端点 vs 统一入口，返回归一化结果
    // ------------------------------------------------------------------

    private HttpHeaders headers(String version, String client, String idemKey, String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (version != null) {
            h.add("X-Api-Version", version);
        }
        if (client != null) {
            h.add("X-Client-Id", client);
        }
        if (idemKey != null) {
            h.add("Idempotency-Key", idemKey);
        }
        if (requestId != null) {
            h.add("X-Request-Id", requestId);
        }
        return h;
    }

    private Outcome callOrders(String version, String client, String idemKey,
                               String requestId, String product, int qty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("product", product);
        body.put("quantity", qty);
        body.put("amount", 19.99);
        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(body, headers(version, client, idemKey, requestId));
        ResponseEntity<String> resp = rest.exchange(base + "/api/orders",
                HttpMethod.POST, entity, String.class);
        return normalize(resp, requestId);
    }

    private Outcome callUnified(String version, String client, String idemKey,
                                String requestId, String product, int qty) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("product", product);
        params.put("quantity", qty);
        params.put("amount", 19.99);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("capability", "orders");
        body.put("operation", "createOrder");
        body.put("params", params);
        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(body, headers(version, client, idemKey, requestId));
        ResponseEntity<String> resp = rest.exchange(base + "/api/internal/invoke",
                HttpMethod.POST, entity, String.class);
        return normalize(resp, requestId);
    }

    private Outcome normalize(ResponseEntity<String> resp, String requestIdHeader) {
        HttpHeaders h = resp.getHeaders();
        String code = null;
        String body = resp.getBody();
        try {
            JsonNode node = mapper.readTree(body);
            if (node.has("code")) {
                code = node.path("code").asText();
                // 两次独立请求各自生成 requestId；一致性对比时归一化，仅比较 code/message 语义
                com.fasterxml.jackson.databind.node.ObjectNode on =
                        (com.fasterxml.jackson.databind.node.ObjectNode) node;
                on.put("requestId", "<requestId>");
                body = mapper.writeValueAsString(on);
            }
        } catch (Exception ignore) {
            // 非 JSON 或空体
        }
        return new Outcome(resp.getStatusCode().value(), body,
                h.getFirst("X-Api-Version-Selected"),
                h.getFirst("Deprecation"), h.getFirst("Sunset"),
                h.getFirst("X-Idempotency-Outcome"),
                h.getFirst("X-Request-Id") != null ? h.getFirst("X-Request-Id") : requestIdHeader,
                code);
    }

    private void assertParity(String scenario, Outcome api, Outcome unified) {
        log.info("[test-parity] 场景={} | 业务端点 status={} code={} selected={} dep={} idem={} body={}",
                scenario, api.status(), api.errorCode(), api.selectedVersion(),
                api.deprecation(), api.idemOutcome(), api.body());
        log.info("[test-parity] 场景={} | 统一入口 status={} code={} selected={} dep={} idem={} body={}",
                scenario, unified.status(), unified.errorCode(), unified.selectedVersion(),
                unified.deprecation(), unified.idemOutcome(), unified.body());
        assertEquals(api.status(), unified.status(), scenario + ": HTTP 状态必须一致");
        assertEquals(api.errorCode(), unified.errorCode(), scenario + ": 拒绝原因 code 必须一致");
        assertEquals(api.selectedVersion(), unified.selectedVersion(),
                scenario + ": 选中版本必须一致");
        assertEquals(api.deprecation(), unified.deprecation(), scenario + ": Deprecation 必须一致");
        assertEquals(api.sunset() == null, unified.sunset() == null,
                scenario + ": Sunset 有无必须一致");
        assertEquals(api.idemOutcome(), unified.idemOutcome(),
                scenario + ": 幂等结论必须一致");
        assertEquals(api.body(), unified.body(), scenario + ": 响应体必须一致");
    }

    // ------------------------------------------------------------------
    // 场景：正常 / 拒绝（用不同 key 让两入口分别“首次”执行，再对比）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("正常成功：两入口在 1.x 精简与 2.x/3.x 扩展形态下结果逐项一致")
    void successParityAcrossVersions() {
        String[][] cases = {{"1.2", "client-legacy-1"}, {"2.0", "client-mobile-1"},
                {"3.0", "client-mobile-1"}};
        int suffix = 0;
        for (String[] c : cases) {
            suffix++;
            String sharedKey = "parity-ok-shared-" + suffix;
            // 先经业务端点首次执行
            Outcome first = callOrders(c[0], c[1], sharedKey,
                    "trace-ok-first-" + suffix, "widget", 2);
            assertEquals(200, first.status());
            assertEquals("EXECUTED", first.idemOutcome());
            // 同一幂等键：两入口都重放，得到的响应体/结论必须逐项一致
            Outcome api = callOrders(c[0], c[1], sharedKey,
                    "trace-ok-api-" + suffix, "widget", 2);
            Outcome uni = callUnified(c[0], c[1], sharedKey,
                    "trace-ok-uni-" + suffix, "widget", 2);
            assertParity("正常成功(同键重放) v=" + c[0], api, uni);
            assertEquals("REPLAYED", api.idemOutcome());
            assertEquals(c[0], api.selectedVersion());
        }
        assertEquals(3, orderService.sideEffectCount(), "每个版本仅首次执行一次，跨入口重放无副作用");
    }

    @Test
    @DisplayName("弃用版本：两入口都带 Deprecation/Sunset 且正常成功")
    void deprecatedParity() {
        String depKey = "parity-dep-shared";
        assertEquals("EXECUTED", callOrders("1.0", "client-legacy-1", depKey,
                "trace-dep-first", "gadget", 1).idemOutcome());
        Outcome api = callOrders("1.0", "client-legacy-1", depKey,
                "trace-dep-api", "gadget", 1);
        Outcome uni = callUnified("1.0", "client-legacy-1", depKey,
                "trace-dep-uni", "gadget", 1);
        assertParity("弃用 1.0", api, uni);
        assertEquals("true", api.deprecation());
        assertTrue(api.sunset() != null && !api.sunset().isBlank());
    }

    @Test
    @DisplayName("版本拒绝：缺失/未知/跨主版本不兼容在两入口 code 与状态一致且可区分")
    void versionRejectionParity() {
        // 缺版本
        assertParity("缺版本",
                callOrders(null, "client-mobile-1", "parity-nv-api", null, "widget", 1),
                callUnified(null, "client-mobile-1", "parity-nv-uni", null, "widget", 1));
        // 版本不可识别
        assertParity("版本格式错误",
                callOrders("v2", "client-mobile-1", "parity-bv-api", null, "widget", 1),
                callUnified("v2", "client-mobile-1", "parity-bv-uni", null, "widget", 1));
        // 未知主版本
        assertParity("未知主版本",
                callOrders("9.0", "client-mobile-1", "parity-uv-api", null, "widget", 1),
                callUnified("9.0", "client-mobile-1", "parity-uv-uni", null, "widget", 1));
        // 已退役主版本（跨主版本不兼容）
        Outcome api = callOrders("0.1", "client-mobile-1", "parity-iv-api", null, "widget", 1);
        Outcome uni = callUnified("0.1", "client-mobile-1", "parity-iv-uni", null, "widget", 1);
        assertParity("退役主版本", api, uni);
        assertEquals(400, api.status());
        assertEquals("VERSION_INCOMPATIBLE", api.errorCode());
    }

    @Test
    @DisplayName("鉴权拒绝：未认证 401 与越权 403 在两入口一致；且认证先于版本/能力判定")
    void authRejectionParity() {
        // 未认证（且版本故意错误）：仍先报 401
        Outcome api = callOrders("9.9", null, "parity-ua-api", null, "widget", 1);
        Outcome uni = callUnified("9.9", null, "parity-ua-uni", null, "widget", 1);
        assertParity("未认证", api, uni);
        assertEquals(401, api.status());
        assertEquals("AUTH_UNAUTHENTICATED", api.errorCode());

        // 未认证 + 未知能力：同样 401（不暴露能力是否存在）
        Map<String, Object> body = Map.of("capability", "ghost", "operation", "x",
                "params", Map.of());
        HttpHeaders h = headers(null, null, null, null);
        ResponseEntity<String> ghost = rest.exchange(base + "/api/internal/invoke",
                HttpMethod.POST, new HttpEntity<>(body, h), String.class);
        assertEquals(401, ghost.getStatusCode().value(), "未认证时未知能力不得暴露为 404");
        log.info("[test-parity] 场景=未认证+未知能力 status={}（统一入口不暴露能力存在性）",
                ghost.getStatusCode().value());

        // 已认证但只读客户端写：403
        Outcome fApi = callOrders("2.0", "client-readonly-1", "parity-fb-api",
                null, "widget", 1);
        Outcome fUni = callUnified("2.0", "client-readonly-1", "parity-fb-uni",
                null, "widget", 1);
        assertParity("越权写", fApi, fUni);
        assertEquals(403, fApi.status());
        assertEquals("AUTH_FORBIDDEN", fApi.errorCode());
    }

    @Test
    @DisplayName("缺幂等键与业务拒绝在两入口一致（400 IDEMPOTENCY_KEY_MISSING / 422 BUSINESS_ERROR）")
    void idemKeyMissingAndBusinessParity() {
        assertParity("缺幂等键",
                callOrders("2.0", "client-mobile-1", null, null, "widget", 1),
                callUnified("2.0", "client-mobile-1", null, null, "widget", 1));
        assertParity("业务拒绝",
                callOrders("2.0", "client-mobile-1", "parity-biz-api",
                        null, "forbidden-product", 1),
                callUnified("2.0", "client-mobile-1", "parity-biz-uni",
                        null, "forbidden-product", 1));
    }

    @Test
    @DisplayName("读路径：两入口未授权读与资源不存在同为 404，且正常读取返回一致形态")
    void readPathParityAndExistenceHiding() {
        // 先通过业务端点造一个真实订单
        Outcome created = callOrders("2.0", "client-mobile-1", "parity-read-setup",
                "trace-read-setup", "widget", 1);
        String orderId = extractOrderId(created.body());
        log.info("[test-parity] 场景=读路径 已建订单 orderId={}", orderId);

        // 正常读取（业务 GET vs 统一 getOrder）
        ResponseEntity<String> getApi = rest.exchange(
                base + "/api/orders/" + orderId, HttpMethod.GET,
                new HttpEntity<>(headers("2.0", "client-mobile-1", null, null)), String.class);
        ResponseEntity<String> getUni = invokeGetOrder("2.0", "client-mobile-1", orderId);
        assertEquals(getApi.getStatusCode().value(), getUni.getStatusCode().value());
        assertEquals(getApi.getBody(), getUni.getBody(), "两入口正常读取响应体必须一致");
        log.info("[test-parity] 场景=正常读取 业务端点 body={} | 统一入口 body={}",
                getApi.getBody(), getUni.getBody());

        // 未授权读“存在”的资源 与 资源“不存在”：两入口都同为 404
        int deniedExistingApi = getStatus(() -> rest.exchange(
                base + "/api/orders/" + orderId, HttpMethod.GET,
                new HttpEntity<>(headers("2.0", "client-noaccess-1", null, null)), String.class));
        int missingApi = getStatus(() -> rest.exchange(
                base + "/api/orders/ORD-99999999", HttpMethod.GET,
                new HttpEntity<>(headers("2.0", "client-mobile-1", null, null)), String.class));
        int deniedExistingUni = invokeGetOrder("2.0", "client-noaccess-1", orderId)
                .getStatusCode().value();
        int missingUni = invokeGetOrder("2.0", "client-mobile-1", "ORD-99999999")
                .getStatusCode().value();
        log.info("[test-parity] 场景=读不泄露存在性 业务端点[越权存在={},不存在={}] 统一入口[越权存在={},不存在={}]",
                deniedExistingApi, missingApi, deniedExistingUni, missingUni);
        assertEquals(404, deniedExistingApi);
        assertEquals(404, missingApi);
        assertEquals(404, deniedExistingUni);
        assertEquals(404, missingUni);
    }

    // ------------------------------------------------------------------
    // 真实并发：同一个幂等键在两入口混合并发，只执行一次，其余 IN_PROGRESS/REPLAYED
    // ------------------------------------------------------------------

    @Test
    @DisplayName("真实并发：同一幂等键在两入口混合并发提交，业务恰好一次，两入口结论集合一致")
    void concurrentSameKeyAcrossBothEntries() throws Exception {
        int perEntry = 12;
        int total = perEntry * 2;
        CyclicBarrier barrier = new CyclicBarrier(total);
        ExecutorService pool = Executors.newFixedThreadPool(total);
        ConcurrentLinkedQueue<Outcome> apiOutcomes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Outcome> uniOutcomes = new ConcurrentLinkedQueue<>();
        AtomicInteger executing = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(total);
        try {
            for (int i = 0; i < perEntry; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        apiOutcomes.add(callOrders("2.0", "client-mobile-1",
                                "shared-concurrent-key", null, "widget", 1));
                    } catch (Exception e) {
                        executing.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
                pool.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        uniOutcomes.add(callUnified("2.0", "client-mobile-1",
                                "shared-concurrent-key", null, "widget", 1));
                    } catch (Exception e) {
                        executing.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        Map<String, Integer> apiCounts = tally(apiOutcomes);
        Map<String, Integer> uniCounts = tally(uniOutcomes);
        log.info("[test-parity] 场景=同键两入口混合并发 业务端点结论分布={} 统一入口结论分布={}",
                apiCounts, uniCounts);

        int executed = apiCounts.getOrDefault("EXECUTED", 0) + uniCounts.getOrDefault("EXECUTED", 0);
        int conflict = apiCounts.getOrDefault("KEY_CONFLICT", 0) + uniCounts.getOrDefault("KEY_CONFLICT", 0);
        assertEquals(1, executed, "两入口共享同一幂等存储，同键全局只能 EXECUTED 一次");
        assertEquals(0, conflict, "同键同负载不得出现键冲突");
        assertEquals(1, orderService.sideEffectCount(), "业务副作用恰好一次（跨入口）");

        // 两入口都必须观察到“非执行”结论（IN_PROGRESS 或 REPLAYED），没有一方重复执行
        assertTrue(apiOutcomes.stream().anyMatch(o -> "IN_PROGRESS".equals(o.idemOutcome())
                        || "REPLAYED".equals(o.idemOutcome())),
                "业务端点必须观察到 IN_PROGRESS/REPLAYED");
        assertTrue(uniOutcomes.stream().anyMatch(o -> "IN_PROGRESS".equals(o.idemOutcome())
                        || "REPLAYED".equals(o.idemOutcome())),
                "统一入口必须观察到 IN_PROGRESS/REPLAYED");

        // 串行重放阶段：两入口后续同键请求都必须 REPLAYED 且响应体与首次完全一致
        Outcome replayApi = callOrders("2.0", "client-mobile-1",
                "shared-concurrent-key", null, "widget", 1);
        Outcome replayUni = callUnified("2.0", "client-mobile-1",
                "shared-concurrent-key", null, "widget", 1);
        log.info("[test-parity] 场景=并发后串行重放 api.idem={} uni.idem={} bodyEqual={}",
                replayApi.idemOutcome(), replayUni.idemOutcome(),
                replayApi.body().equals(replayUni.body()));
        assertEquals("REPLAYED", replayApi.idemOutcome());
        assertEquals("REPLAYED", replayUni.idemOutcome());
        assertEquals(replayApi.body(), replayUni.body(), "两入口重放结果必须一致");
        assertEquals(1, orderService.sideEffectCount(), "重放不产生额外副作用");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private ResponseEntity<String> invokeGetOrder(String version, String client, String orderId) {
        Map<String, Object> body = Map.of("capability", "orders", "operation", "getOrder",
                "params", Map.of("orderId", orderId));
        return rest.exchange(base + "/api/internal/invoke", HttpMethod.POST,
                new HttpEntity<>(body, headers(version, client, null, null)), String.class);
    }

    private interface Call {
        ResponseEntity<String> run() throws Exception;
    }

    private int getStatus(Call call) {
        try {
            return call.run().getStatusCode().value();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String extractOrderId(String body) {
        try {
            return mapper.readTree(body).path("orderId").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, Integer> tally(ConcurrentLinkedQueue<Outcome> outcomes) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Outcome o : outcomes) {
            String key = o.status() == 200 ? o.idemOutcome() : o.errorCode();
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }
}
