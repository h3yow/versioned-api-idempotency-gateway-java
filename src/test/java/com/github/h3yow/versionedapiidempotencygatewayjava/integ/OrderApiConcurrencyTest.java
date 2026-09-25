package com.github.h3yow.versionedapiidempotencygatewayjava.tests.integ;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.versionedapiidempotencygatewayjava.audit.AuditLogger;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.AuditEvent;
import com.github.h3yow.versionedapiidempotencygatewayjava.web.handler.OrderSideEffectCounter;
import com.github.h3yow.versionedapiidempotencygatewayjava.web.handler.V2OrderHandler;
import org.junit.jupiter.api.AfterEach;
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

import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 幂等并发端到端测试：
 * 同一 Idempotency-Key 的两个真实 HTTP 调用并发到达时，
 * 首个执行业务（中途阻塞），第二个必须立即返回 409 IDEMPOTENCY_IN_PROGRESS，
 * 且全程业务副作用恰好一次。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(OrderApiConcurrencyTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OrderSideEffectCounter sideEffectCounter;
    @Autowired
    private AuditLogger auditLogger;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        sideEffectCounter.reset();
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        V2OrderHandler.testBlocker = () -> { };
        pool.shutdownNow();
    }

    @Test
    @DisplayName("同键并发：第二请求 409 IDEMPOTENCY_IN_PROGRESS，首个成功，副作用一次")
    void concurrentDuplicateKeyOverHttp() throws Exception {
        String key = "k-http-concurrent";
        String body = objectMapper.writeValueAsString(new LinkedHashMap<>() {{
            put("item", "BLOCKING");
            put("quantity", 7);
        }});

        CountDownLatch enteredAction = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        V2OrderHandler.testBlocker = () -> {
            enteredAction.countDown();
            try {
                releaseAction.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Future<MvcResult> firstFuture = pool.submit(() -> mockMvc.perform(post("/api/orders")
                .header("X-Api-Version", "2.0")
                .header("X-Client-Id", "client-a")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andReturn());

        // 等待首个请求确实进入业务动作（IN_PROGRESS 窗口已打开）
        assertTrue(enteredAction.await(5, TimeUnit.SECONDS), "首个请求应进入业务动作");

        MvcResult second = mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andReturn();
        log.info("[并发HTTP] 第二请求响应 code={} message={}",
                objectMapper.readTree(second.getResponse().getContentAsString()).path("code").asText(),
                objectMapper.readTree(second.getResponse().getContentAsString()).path("message").asText());

        releaseAction.countDown();
        MvcResult first = firstFuture.get(5, TimeUnit.SECONDS);
        log.info("[并发HTTP] 首个请求状态=200 body={}", first.getResponse().getContentAsString());

        assertEquals(1, sideEffectCounter.get(), "并发下业务副作用只能发生一次");

        // 首个请求完成后的第三次提交应回放冻结结果
        mockMvc.perform(post("/api/orders")
                        .header("X-Api-Version", "2.0")
                        .header("X-Client-Id", "client-a")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotency.status").value("REPLAY_SUCCEEDED"));
        assertEquals(1, sideEffectCounter.get(), "回放不产生副作用");

        for (AuditEvent e : auditLogger.events()) {
            if (key.equals(e.idempotencyStatus()) || e.outcome().contains("IN_PROGRESS")) {
                log.info("[并发审计] requestId={} 版本决策={} 幂等={} 权限={} 结果={}",
                        e.requestId(), e.versionDecision(), e.idempotencyStatus(),
                        e.authorization(), e.outcome());
            }
        }
    }
}
