package com.github.h3yow.versionedapiidempotencygatewayjava.tests.web;

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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两个入口一致性测试：业务接口（/api/orders）与内部统一入口（/internal/invoke）
 * 在正常与各类异常下给出一致结果；一致性用真实并发请求验证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalInvokeParityTest {

    private static final Logger log = LoggerFactory.getLogger(InternalInvokeParityTest.class);

    @Autowired
    private TestRestTemplate rest;
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

    // ---------- 请求构造 ----------

    private HttpHeaders headers(String version, String clientId, String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (version != null) {
            h.set("X-Api-Version", version);
        }
        if (clientId != null) {
            h.set("X-Client-Id", clientId);
        }
        if (idempotencyKey != null) {
            h.set("Idempotency-Key", idempotencyKey);
        }
        return h;
    }

    private Map<String, Object> orderBody(String product) {
        return Map.of("product", product, "quantity", 1, "amount", new BigDecimal("19.99"));
    }

    private Map<String, Object> invokeEnvelope(String operation, Map<String, Object> params) {
        return Map.of("capability", "orders", "operation", operation, "params", params);
    }

    /** 业务接口创建订单。 */
    private ResponseEntity<String> apiCreate(String version, String clientId,
                                             String key, String product) {
        return rest.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(orderBody(product), headers(version, clientId, key)),
                String.class);
    }

    /** 统一入口创建订单。 */
    private ResponseEntity<String> invokeCreate(String version, String clientId,
                                                String key, String product) {
        return rest.exchange("/internal/invoke", HttpMethod.POST,
                new HttpEntity<>(invokeEnvelope("create", orderBody(product)),
                        headers(version, clientId, key)),
                String.class);
    }

    /** 业务接口查询订单。 */
    private ResponseEntity<String> apiGet(String version, String clientId, String orderId) {
        return rest.exchange("/api/orders/" + orderId, HttpMethod.GET,
                new HttpEntity<>(headers(version, clientId, null)), String.class);
    }

    /** 统一入口查询订单。 */
    private ResponseEntity<String> invokeGet(String version, String clientId, String orderId) {
        return rest.exchange("/internal/invoke", HttpMethod.POST,
                new HttpEntity<>(invokeEnvelope("get", Map.of("orderId", orderId)),
                        headers(version, clientId, null)),
                String.class);
    }

    private void logParity(String scenario, ResponseEntity<String> api, ResponseEntity<String> invoke) {
        log.info("[test-parity] scenario={} api={}/{} invoke={}/{} "
                        + "apiSelected={} invokeSelected={} apiOutcome={} invokeOutcome={}",
                scenario,
                api.getStatusCode().value(), codeOf(api),
                invoke.getStatusCode().value(), codeOf(invoke),
                api.getHeaders().getFirst("X-Api-Version-Selected"),
                invoke.getHeaders().getFirst("X-Api-Version-Selected"),
                api.getHeaders().getFirst("X-Idempotency-Outcome"),
                invoke.getHeaders().getFirst("X-Idempotency-Outcome"));
    }

    private String codeOf(ResponseEntity<String> response) {
        try {
            JsonNode body = objectMapper.readTree(response.getBody());
            return body.has("code") ? body.path("code").asText() : "-";
        } catch (Exception e) {
            return "-";
        }
    }

    private void assertSameError(String scenario,
                                 ResponseEntity<String> api, ResponseEntity<String> invoke,
                                 int expectedStatus, String expectedCode) {
        logParity(scenario, api, invoke);
        assertEquals(expectedStatus, api.getStatusCode().value(), scenario + "：业务接口状态码");
        assertEquals(expectedStatus, invoke.getStatusCode().value(), scenario + "：统一入口状态码");
        assertEquals(expectedCode, codeOf(api), scenario + "：业务接口错误码");
        assertEquals(expectedCode, codeOf(invoke), scenario + "：统一入口错误码");
    }

    // ---------- 正常路径一致性 ----------

    @Test
    @DisplayName("正常创建：统一入口与业务接口返回相同形态、相同选中版本与幂等结论")
    void successParity() throws Exception {
        ResponseEntity<String> api = apiCreate("2.0", "client-mobile-1", "parity-ok-api", "widget");
        ResponseEntity<String> invoke = invokeCreate("2.0", "client-mobile-1", "parity-ok-invoke", "widget");
        logParity("success", api, invoke);

        assertEquals(200, api.getStatusCode().value());
        assertEquals(200, invoke.getStatusCode().value());
        assertEquals("2.0", invoke.getHeaders().getFirst("X-Api-Version-Selected"));
        assertEquals("EXECUTED", invoke.getHeaders().getFirst("X-Idempotency-Outcome"));

        JsonNode apiBody = objectMapper.readTree(api.getBody());
        JsonNode invokeBody = objectMapper.readTree(invoke.getBody());
        // 形态一致：除订单号外所有字段相同
        assertEquals(apiBody.path("viewProfile"), invokeBody.path("viewProfile"));
        assertEquals(apiBody.path("product"), invokeBody.path("product"));
        assertEquals(apiBody.path("status"), invokeBody.path("status"));
        assertEquals(apiBody.path("capabilities"), invokeBody.path("capabilities"));
        assertEquals(2, orderService.sideEffectCount());
    }

    @Test
    @DisplayName("跨入口幂等：同一键同一负载，先走业务接口再走统一入口 -> REPLAYED 且响应体逐字节一致")
    void idempotencySharedAcrossEntries() throws Exception {
        ResponseEntity<String> first = apiCreate("2.0", "client-mobile-1", "parity-xentry", "widget");
        ResponseEntity<String> second = invokeCreate("2.0", "client-mobile-1", "parity-xentry", "widget");
        logParity("cross-entry-replay", first, second);

        assertEquals("EXECUTED", first.getHeaders().getFirst("X-Idempotency-Outcome"));
        assertEquals("REPLAYED", second.getHeaders().getFirst("X-Idempotency-Outcome"));
        assertEquals(first.getBody(), second.getBody(), "跨入口重放必须返回与首次一致的响应体");
        assertEquals(1, orderService.sideEffectCount(), "跨入口重复提交不得重复执行");

        // 反向：先统一入口再业务接口
        ResponseEntity<String> third = invokeCreate("2.0", "client-mobile-1", "parity-xentry-2", "gadget");
        ResponseEntity<String> fourth = apiCreate("2.0", "client-mobile-1", "parity-xentry-2", "gadget");
        assertEquals("EXECUTED", third.getHeaders().getFirst("X-Idempotency-Outcome"));
        assertEquals("REPLAYED", fourth.getHeaders().getFirst("X-Idempotency-Outcome"));
        assertEquals(third.getBody(), fourth.getBody());
        assertEquals(2, orderService.sideEffectCount());
    }

    @Test
    @DisplayName("版本与弃用信号一致：1.5 两入口都落到 1.2；1.0 两入口都带 Deprecation/Sunset")
    void versionAndDeprecationParity() {
        ResponseEntity<String> apiFallback = apiCreate("1.5", "client-mobile-1", "parity-v15-a", "widget");
        ResponseEntity<String> invokeFallback = invokeCreate("1.5", "client-mobile-1", "parity-v15-i", "widget");
        logParity("minor-fallback", apiFallback, invokeFallback);
        assertEquals("1.2", apiFallback.getHeaders().getFirst("X-Api-Version-Selected"));
        assertEquals("1.2", invokeFallback.getHeaders().getFirst("X-Api-Version-Selected"));

        ResponseEntity<String> apiDepr = apiCreate("1.0", "client-legacy-1", "parity-v10-a", "widget");
        ResponseEntity<String> invokeDepr = invokeCreate("1.0", "client-legacy-1", "parity-v10-i", "widget");
        logParity("deprecated", apiDepr, invokeDepr);
        for (ResponseEntity<String> r : new ResponseEntity[]{apiDepr, invokeDepr}) {
            assertEquals(200, r.getStatusCode().value());
            assertEquals("true", r.getHeaders().getFirst("Deprecation"));
            assertNotNull(r.getHeaders().getFirst("Sunset"));
        }
    }

    // ---------- 异常路径一致性 ----------

    @Test
    @DisplayName("异常矩阵：缺版本/未知版本/跨主版本/未认证/越权/缺幂等键/键冲突/业务异常，两入口状态码与错误码一致")
    void errorParityMatrix() {
        assertSameError("missing-version",
                apiCreate(null, "client-mobile-1", "pm-1", "widget"),
                invokeCreate(null, "client-mobile-1", "pm-2", "widget"),
                400, "VERSION_MISSING");

        assertSameError("unknown-version",
                apiCreate("9.0", "client-mobile-1", "pm-3", "widget"),
                invokeCreate("9.0", "client-mobile-1", "pm-4", "widget"),
                404, "VERSION_UNKNOWN");

        assertSameError("incompatible-version",
                apiCreate("0.1", "client-mobile-1", "pm-5", "widget"),
                invokeCreate("0.1", "client-mobile-1", "pm-6", "widget"),
                400, "VERSION_INCOMPATIBLE");

        assertSameError("unauthenticated",
                apiCreate("2.0", null, "pm-7", "widget"),
                invokeCreate("2.0", null, "pm-8", "widget"),
                401, "AUTH_UNAUTHENTICATED");

        assertSameError("forbidden",
                apiCreate("2.0", "client-readonly-1", "pm-9", "widget"),
                invokeCreate("2.0", "client-readonly-1", "pm-10", "widget"),
                403, "AUTH_FORBIDDEN");

        assertSameError("missing-idempotency-key",
                apiCreate("2.0", "client-mobile-1", null, "widget"),
                invokeCreate("2.0", "client-mobile-1", null, "widget"),
                400, "IDEMPOTENCY_KEY_MISSING");

        // 键冲突：先各自成功，再同键不同负载
        apiCreate("2.0", "client-mobile-1", "pm-shared", "widget");
        invokeCreate("2.0", "client-mobile-1", "pm-shared", "widget"); // REPLAYED，同负载
        assertSameError("key-conflict",
                apiCreate("2.0", "client-mobile-1", "pm-shared", "different-product"),
                invokeCreate("2.0", "client-mobile-1", "pm-shared", "another-product"),
                409, "IDEMPOTENCY_KEY_CONFLICT");

        assertSameError("business-error",
                apiCreate("2.0", "client-mobile-1", "pm-11", "forbidden-product"),
                invokeCreate("2.0", "client-mobile-1", "pm-12", "forbidden-product"),
                422, "BUSINESS_ERROR");

        assertEquals(1, orderService.sideEffectCount(), "异常矩阵中只有第一次合法创建产生副作用");
    }

    @Test
    @DisplayName("未授权查询与不存在资源：两入口同为 404，不泄露存在性")
    void unauthorizedGetHidesExistenceOnBothEntries() throws Exception {
        ResponseEntity<String> created = apiCreate("2.0", "client-mobile-1", "parity-get", "widget");
        String orderId = objectMapper.readTree(created.getBody()).path("orderId").asText();

        ResponseEntity<String> apiDenied = apiGet("2.0", "client-noaccess-1", orderId);
        ResponseEntity<String> invokeDenied = invokeGet("2.0", "client-noaccess-1", orderId);
        ResponseEntity<String> apiMissing = apiGet("2.0", "client-mobile-1", "ORD-99999999");
        ResponseEntity<String> invokeMissing = invokeGet("2.0", "client-mobile-1", "ORD-99999999");
        logParity("get-denied", apiDenied, invokeDenied);
        logParity("get-missing", apiMissing, invokeMissing);

        for (ResponseEntity<String> r :
                new ResponseEntity[]{apiDenied, invokeDenied, apiMissing, invokeMissing}) {
            assertEquals(404, r.getStatusCode().value(), "越权与不存在在两入口都必须表现为 404");
        }

        // 有权限查询：两入口返回同一资源、同一形态
        ResponseEntity<String> apiOk = apiGet("2.0", "client-mobile-1", orderId);
        ResponseEntity<String> invokeOk = invokeGet("2.0", "client-mobile-1", orderId);
        assertEquals(200, apiOk.getStatusCode().value());
        assertEquals(200, invokeOk.getStatusCode().value());
        assertEquals(objectMapper.readTree(apiOk.getBody()).path("orderId"),
                objectMapper.readTree(invokeOk.getBody()).path("orderId"));
    }

    @Test
    @DisplayName("统一入口信封校验：未知能力/未知操作/非法信封的错误可区分")
    void envelopeValidation() {
        ResponseEntity<String> unknownCapability = rest.exchange("/internal/invoke", HttpMethod.POST,
                new HttpEntity<>(Map.of("capability", "payments", "operation", "create",
                        "params", Map.of()), headers("2.0", "client-mobile-1", "env-1")),
                String.class);
        assertEquals(404, unknownCapability.getStatusCode().value());
        assertEquals("CAPABILITY_UNKNOWN", codeOf(unknownCapability));

        ResponseEntity<String> unknownOperation = rest.exchange("/internal/invoke", HttpMethod.POST,
                new HttpEntity<>(Map.of("capability", "orders", "operation", "delete",
                        "params", Map.of()), headers("2.0", "client-mobile-1", "env-2")),
                String.class);
        assertEquals(404, unknownOperation.getStatusCode().value());
        assertEquals("OPERATION_UNKNOWN", codeOf(unknownOperation));

        ResponseEntity<String> invalid = rest.exchange("/internal/invoke", HttpMethod.POST,
                new HttpEntity<>(Map.of("params", Map.of()),
                        headers("2.0", "client-mobile-1", "env-3")),
                String.class);
        assertEquals(400, invalid.getStatusCode().value());
        assertEquals("INVOCATION_INVALID", codeOf(invalid));
        log.info("[test-parity] 信封校验 capability={} operation={} invalid={}",
                codeOf(unknownCapability), codeOf(unknownOperation), codeOf(invalid));
    }

    // ---------- 真实并发下的两入口一致性 ----------

    @Test
    @DisplayName("真实并发：同一幂等键同时打到两个入口，副作用恰好一次，成功响应体一致")
    void concurrentSameKeyAcrossBothEntries() throws Exception {
        int threads = 12; // 一半打业务接口，一半打统一入口
        String key = "parity-concurrent";
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger inProgress = new AtomicInteger();
        List<String> successBodies = new ArrayList<>();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final boolean useApi = i % 2 == 0;
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        ResponseEntity<String> r = useApi
                                ? apiCreate("2.0", "client-mobile-1", key, "widget")
                                : invokeCreate("2.0", "client-mobile-1", key, "widget");
                        String outcome = r.getHeaders().getFirst("X-Idempotency-Outcome");
                        log.info("[test-parity] entry={} status={} outcome={}",
                                useApi ? "api" : "invoke", r.getStatusCode().value(), outcome);
                        if (r.getStatusCode().value() == 200) {
                            synchronized (successBodies) {
                                successBodies.add(r.getBody());
                            }
                            if ("EXECUTED".equals(outcome)) {
                                executed.incrementAndGet();
                            } else if ("REPLAYED".equals(outcome)) {
                                replayed.incrementAndGet();
                            }
                        } else {
                            assertEquals(409, r.getStatusCode().value(),
                                    "并发下非成功响应只能是幂等进行中冲突");
                            assertEquals("IDEMPOTENCY_IN_PROGRESS", codeOf(r));
                            inProgress.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        log.info("[test-parity] 并发统计 executed={} replayed={} inProgress={} sideEffects={}",
                executed.get(), replayed.get(), inProgress.get(), orderService.sideEffectCount());
        assertEquals(1, executed.get(), "两个入口合计只能有一个请求真正执行");
        assertEquals(1, orderService.sideEffectCount(), "业务副作用必须恰好一次");
        assertTrue(successBodies.size() >= 1);
        String firstBody = successBodies.get(0);
        for (String body : successBodies) {
            assertEquals(firstBody, body, "所有成功响应（无论来自哪个入口）必须逐字节一致");
        }

        // 并发平息后：两个入口各自重放，结论与响应体仍一致
        ResponseEntity<String> apiReplay = apiCreate("2.0", "client-mobile-1", key, "widget");
        ResponseEntity<String> invokeReplay = invokeCreate("2.0", "client-mobile-1", key, "widget");
        assertEquals("REPLAYED", apiReplay.getHeaders().getFirst("X-Idempotency-Outcome"));
        assertEquals("REPLAYED", invokeReplay.getHeaders().getFirst("X-Idempotency-Outcome"));
        assertEquals(apiReplay.getBody(), invokeReplay.getBody());
        assertEquals(1, orderService.sideEffectCount());
    }
}
