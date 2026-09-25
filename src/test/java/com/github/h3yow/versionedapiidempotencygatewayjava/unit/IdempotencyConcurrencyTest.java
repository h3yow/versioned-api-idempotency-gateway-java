package com.github.h3yow.versionedapiidempotencygatewayjava.tests.unit;

import com.github.h3yow.versionedapiidempotencygatewayjava.exception.GatewayException;
import com.github.h3yow.versionedapiidempotencygatewayjava.idem.IdempotencyRecord;
import com.github.h3yow.versionedapiidempotencygatewayjava.idem.IdempotencyRecordOutcome;
import com.github.h3yow.versionedapiidempotencygatewayjava.idem.InMemoryIdempotencyStore;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.IdempotencyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等存储单元测试：
 * 同一幂等键并发只允许一次业务副作用；进行中/回放/失败重试/键冲突可区分。
 */
class IdempotencyConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyConcurrencyTest.class);

    private InMemoryIdempotencyStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryIdempotencyStore();
    }

    @Test
    @DisplayName("同一幂等键并发提交：恰好一个 NEW 执行、其余 IN_PROGRESS，副作用仅一次")
    void concurrentSameKeyExecutesOnce() throws Exception {
        String key = "order-key-concurrent";
        int threads = 8;
        AtomicInteger sideEffects = new AtomicInteger();
        CountDownLatch allSubmitted = new CountDownLatch(threads);
        CountDownLatch releaseAction = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<IdempotencyRecordOutcome>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    allSubmitted.countDown();
                    // 等待所有任务都已进入并发起点，再各自调用幂等存储
                    await(allSubmitted);
                    return store.executeOnce(key, "fp-1", () -> {
                        sideEffects.incrementAndGet();
                        await(releaseAction);
                        return "ORDER-1";
                    });
                }));
            }

            // 让唯一执行者在业务动作中停留足够久，使其他并发者观察到 IN_PROGRESS
            Thread.sleep(300);
            releaseAction.countDown();

            int newCount = 0;
            int inProgressCount = 0;
            for (Future<IdempotencyRecordOutcome> f : futures) {
                IdempotencyStatus s = f.get(5, TimeUnit.SECONDS).status();
                log.info("[幂等并发] key={} 线程结果={}", key, s);
                if (s == IdempotencyStatus.NEW) {
                    newCount++;
                } else if (s == IdempotencyStatus.IN_PROGRESS) {
                    inProgressCount++;
                }
            }
            log.info("[幂等并发] key={} NEW={} IN_PROGRESS={} 副作用次数={}",
                    key, newCount, inProgressCount, sideEffects.get());
            assertEquals(1, newCount, "恰好一个请求首次执行业务");
            assertEquals(threads - 1, inProgressCount, "其余并发请求应为进行中重试");
            assertEquals(1, sideEffects.get(), "业务副作用只能发生一次");
            assertEquals(IdempotencyRecord.State.SUCCEEDED, store.peek(key).orElseThrow().getState());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("成功后同键重复提交：REPLAY_SUCCEEDED 且返回与首次一致的结果，无额外副作用")
    void replayAfterSuccessReturnsSameResult() {
        AtomicInteger sideEffects = new AtomicInteger();
        var first = store.executeOnce("key-replay", "fp-1", () -> {
            sideEffects.incrementAndGet();
            return "ORDER-42";
        });
        var second = store.executeOnce("key-replay", "fp-1", () -> {
            sideEffects.incrementAndGet();
            return "ORDER-DIFFERENT";
        });
        log.info("[幂等回放] first={} second={} 副作用={}",
                first.status(), second.status(), sideEffects.get());
        assertEquals(IdempotencyStatus.NEW, first.status());
        assertEquals(IdempotencyStatus.REPLAY_SUCCEEDED, second.status());
        assertEquals("ORDER-42", second.record().getResult(), "回放必须返回首次冻结结果");
        assertEquals(1, sideEffects.get(), "回放不产生副作用");
    }

    @Test
    @DisplayName("失败后同键同体重试：先 FAILED，重试重新执行并成功，状态 RETRY_AFTER_FAILURE")
    void retryAfterFailureReExecutes() {
        AtomicInteger attempts = new AtomicInteger();

        var failed = assertThrows(GatewayException.class, () ->
                store.executeOnce("key-retry", "fp-1", () -> {
                    attempts.incrementAndGet();
                    throw new GatewayException(
                            com.github.h3yow.versionedapiidempotencygatewayjava.exception.ErrorType.BUSINESS_ERROR,
                            "boom");
                }));
        assertEquals(IdempotencyRecord.State.FAILED,
                store.peek("key-retry").orElseThrow().getState(), "首次失败后应记录为 FAILED");
        log.info("[幂等失败重试] 第1次尝试失败={} 状态={}", failed.getMessage(),
                store.peek("key-retry").orElseThrow().getState());

        var retried = store.executeOnce("key-retry", "fp-1", () -> {
            attempts.incrementAndGet();
            return "ORDER-RECOVERED";
        });
        log.info("[幂等失败重试] 重试结果={} payload={} 总尝试次数={}",
                retried.status(), retried.record().getResult(), attempts.get());
        assertEquals(IdempotencyRecord.State.SUCCEEDED, store.peek("key-retry").orElseThrow().getState());
        assertEquals(IdempotencyStatus.RETRY_AFTER_FAILURE, retried.status());
        assertEquals("ORDER-RECOVERED", retried.record().getResult());
        assertEquals(2, attempts.get(), "失败后允许重新执行一次");
    }

    @Test
    @DisplayName("同键不同请求体 => KEY_CONFLICT，原记录不受影响")
    void keyConflictDoesNotOverwrite() {
        store.executeOnce("key-conflict", "fp-A", () -> "ORDER-A");
        var conflict = store.executeOnce("key-conflict", "fp-B", () -> "ORDER-B");
        log.info("[幂等键冲突] 冲突状态={} 保留结果={}", conflict.status(),
                conflict.record().getResult());
        assertEquals(IdempotencyStatus.KEY_CONFLICT, conflict.status());
        assertEquals("ORDER-A", conflict.record().getResult(), "冲突不得覆盖既有结果");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
