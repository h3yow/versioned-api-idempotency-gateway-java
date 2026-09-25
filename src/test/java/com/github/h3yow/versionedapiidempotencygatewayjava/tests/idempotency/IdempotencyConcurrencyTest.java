package com.github.h3yow.versionedapiidempotencygatewayjava.tests.idempotency;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiException;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ErrorCode;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyOutcome;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyResult;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyService;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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
 * 幂等测试：同键并发仅一次副作用、成功重放、失败重试、键冲突。
 */
class IdempotencyConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyConcurrencyTest.class);

    private IdempotencyService service;
    private AtomicInteger sideEffects;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(new IdempotencyStore());
        sideEffects = new AtomicInteger();
    }

    private void logResult(String requestId, String key, IdempotencyResult<String> r) {
        log.info("[test-idem] requestId={} key={} outcome={} value={} errorCode={} errorMessage='{}'",
                requestId, key, r.outcome(), r.value(), r.errorCode(), r.errorMessage());
    }

    @Test
    @DisplayName("同一幂等键并发提交：仅一次 EXECUTED，副作用恰好 1 次，其余为 IN_PROGRESS")
    void concurrentSameKeyProducesSingleSideEffect() throws Exception {
        int threads = 16;
        String key = "key-concurrent-1";
        String fingerprint = "fp-A";
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<IdempotencyResult<String>>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String requestId = "req-concurrent-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return service.execute(key, fingerprint, requestId, () -> {
                        // 模拟业务处理窗口，期间同键请求必须观察到 IN_PROGRESS
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        sideEffects.incrementAndGet();
                        return "ORDER-X";
                    });
                }));
            }
            int executed = 0, inProgress = 0, replayed = 0;
            for (int i = 0; i < threads; i++) {
                IdempotencyResult<String> r = futures.get(i).get(10, TimeUnit.SECONDS);
                logResult("req-concurrent-" + i, key, r);
                switch (r.outcome()) {
                    case EXECUTED -> {
                        executed++;
                        assertEquals("ORDER-X", r.value());
                    }
                    case IN_PROGRESS -> inProgress++;
                    case REPLAYED -> replayed++;
                    default -> throw new AssertionError("意外的幂等结论: " + r.outcome());
                }
            }
            log.info("[test-idem] 统计 executed={} inProgress={} replayed={} sideEffects={}",
                    executed, inProgress, replayed, sideEffects.get());
            assertEquals(1, executed, "有且仅有一个请求获得执行权");
            assertEquals(1, sideEffects.get(), "业务副作用必须恰好发生一次");
            assertEquals(threads - 1, inProgress + replayed, "其余请求不得执行业务");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("成功后重复提交：返回与首次一致的结果，无额外副作用，结论为 REPLAYED")
    void replayAfterSuccessReturnsSameResult() {
        String key = "key-replay-1";
        IdempotencyResult<String> first =
                service.execute(key, "fp-A", "req-replay-1", () -> {
                    sideEffects.incrementAndGet();
                    return "ORDER-Y";
                });
        IdempotencyResult<String> second =
                service.execute(key, "fp-A", "req-replay-2", () -> {
                    sideEffects.incrementAndGet();
                    return "ORDER-Y-CHANGED";
                });
        logResult("req-replay-1", key, first);
        logResult("req-replay-2", key, second);
        assertEquals(IdempotencyOutcome.EXECUTED, first.outcome());
        assertEquals(IdempotencyOutcome.REPLAYED, second.outcome());
        assertEquals("ORDER-Y", second.value(), "重放必须返回首次结果，不得被覆盖");
        assertEquals(1, sideEffects.get(), "重放不得产生副作用");
    }

    @Test
    @DisplayName("失败后重试：首次失败产生 FAILED，同键同负载重试重新执行并成功，副作用仅重试一次")
    void retryAfterFailureReExecutesOnce() {
        String key = "key-failretry-1";
        String fingerprint = "fp-A";
        AtomicInteger attempts = new AtomicInteger();

        IdempotencyResult<String> failed = service.execute(key, fingerprint, "req-fail-1", () -> {
            attempts.incrementAndGet();
            sideEffects.incrementAndGet();
            throw new ApiException(ErrorCode.BUSINESS_ERROR, "商品不可下单");
        });
        logResult("req-fail-1", key, failed);
        assertEquals(IdempotencyOutcome.EXECUTED, failed.outcome());
        assertEquals("BUSINESS_ERROR", failed.errorCode());
        assertNull(failed.value());

        IdempotencyResult<String> retried = service.execute(key, fingerprint, "req-fail-2", () -> {
            attempts.incrementAndGet();
            sideEffects.incrementAndGet();
            return "ORDER-Z";
        });
        logResult("req-fail-2", key, retried);
        assertEquals(IdempotencyOutcome.RETRY_AFTER_FAILURE, retried.outcome(),
                "失败后重试必须可与首次执行区分");
        assertEquals("ORDER-Z", retried.value());
        assertEquals(2, attempts.get(), "重试真实执行了一次业务");
        assertEquals(2, sideEffects.get());
    }

    @Test
    @DisplayName("键冲突：同一幂等键携带不同负载 -> KEY_CONFLICT，拒绝且不产生副作用")
    void sameKeyDifferentPayloadIsConflict() {
        String key = "key-conflict-1";
        IdempotencyResult<String> first =
                service.execute(key, "fp-A", "req-conflict-1", () -> {
                    sideEffects.incrementAndGet();
                    return "ORDER-1";
                });
        IdempotencyResult<String> conflict =
                service.execute(key, "fp-DIFFERENT", "req-conflict-2", () -> {
                    sideEffects.incrementAndGet();
                    return "ORDER-2";
                });
        logResult("req-conflict-1", key, first);
        logResult("req-conflict-2", key, conflict);
        assertEquals(IdempotencyOutcome.EXECUTED, first.outcome());
        assertEquals(IdempotencyOutcome.KEY_CONFLICT, conflict.outcome());
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", conflict.errorCode());
        assertNotNull(conflict.errorMessage());
        assertEquals(1, sideEffects.get(), "冲突请求不得产生副作用");
    }

    @Test
    @DisplayName("进行中重试可区分：第一个请求持有期间，第二个同键请求得到 IN_PROGRESS")
    void inProgressIsObservableAndDistinguishable() throws Exception {
        String key = "key-inprogress-1";
        CyclicBarrier insideAction = new CyclicBarrier(2);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<IdempotencyResult<String>> holder = pool.submit(() ->
                    service.execute(key, "fp-A", "req-holding-1", () -> {
                        try {
                            insideAction.await(5, TimeUnit.SECONDS); // 通知主线程：已进入业务
                            insideAction.await(5, TimeUnit.SECONDS); // 等待主线程发完并发请求
                        } catch (InterruptedException | java.util.concurrent.BrokenBarrierException
                                 | java.util.concurrent.TimeoutException e) {
                            throw new RuntimeException(e);
                        }
                        sideEffects.incrementAndGet();
                        return "ORDER-H";
                    }));
            insideAction.await(5, TimeUnit.SECONDS);

            IdempotencyResult<String> concurrent =
                    service.execute(key, "fp-A", "req-holding-2",
                            () -> {
                                sideEffects.incrementAndGet();
                                return "SHOULD-NOT-RUN";
                            });
            logResult("req-holding-2", key, concurrent);
            assertEquals(IdempotencyOutcome.IN_PROGRESS, concurrent.outcome());
            assertEquals("IDEMPOTENCY_IN_PROGRESS", concurrent.errorCode());
            assertTrue(concurrent.errorMessage().contains("req-holding-1"),
                    "错误信息应指明持有请求标识以便解释");

            insideAction.await(5, TimeUnit.SECONDS);
            IdempotencyResult<String> done = holder.get(10, TimeUnit.SECONDS);
            logResult("req-holding-1", key, done);
            assertEquals("ORDER-H", done.value());
            assertEquals(1, sideEffects.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
