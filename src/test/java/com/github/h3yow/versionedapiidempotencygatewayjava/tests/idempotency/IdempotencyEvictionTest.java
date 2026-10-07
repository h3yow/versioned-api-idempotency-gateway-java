package com.github.h3yow.versionedapiidempotencygatewayjava.tests.idempotency;

import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyCleaner;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyOutcome;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyProperties;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyResult;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyService;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyStatus;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等记录有界保留测试：TTL 过期、容量淘汰，以及清理与并发提交同时发生时的正确性。
 */
class IdempotencyEvictionTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyEvictionTest.class);

    private IdempotencyStore store;
    private IdempotencyService service;
    private IdempotencyProperties properties;
    private IdempotencyCleaner cleaner;
    private AtomicInteger sideEffects;

    @BeforeEach
    void setUp() {
        store = new IdempotencyStore();
        service = new IdempotencyService(store);
        properties = new IdempotencyProperties();
        cleaner = new IdempotencyCleaner(store, properties);
        sideEffects = new AtomicInteger();
    }

    private IdempotencyResult<String> exec(String key, String requestId, String value) {
        IdempotencyResult<String> r = service.execute(key, "fp-" + key, requestId, () -> {
            sideEffects.incrementAndGet();
            return value;
        });
        log.info("[test-evict] requestId={} key={} outcome={} value={} storeSize={}",
                requestId, key, r.outcome(), r.value(), store.size());
        return r;
    }

    @Test
    @DisplayName("TTL 过期：过期键被清理后按新请求处理、可重新执行；未过期键仍然重放")
    void ttlExpiryAllowsReexecution() {
        properties.setTtlSeconds(60);
        long t0 = System.currentTimeMillis();

        IdempotencyResult<String> first = exec("k-ttl", "req-ttl-1", "V1");
        assertEquals(IdempotencyOutcome.EXECUTED, first.outcome());

        // 未过期：清理不掉，重复提交仍重放
        int evictedEarly = cleaner.cleanup(t0 + 30_000);
        IdempotencyResult<String> replay = exec("k-ttl", "req-ttl-2", "V2");
        assertEquals(0, evictedEarly, "未过期记录不得被清理");
        assertEquals(IdempotencyOutcome.REPLAYED, replay.outcome());
        assertEquals("V1", replay.value());
        assertEquals(1, sideEffects.get());

        // 过期：记录被淘汰，同键同负载按新请求重新执行
        int evicted = cleaner.cleanup(t0 + 61_000);
        assertEquals(1, evicted, "过期记录必须被淘汰");
        assertEquals(0, store.size());
        IdempotencyResult<String> reexecuted = exec("k-ttl", "req-ttl-3", "V3");
        assertEquals(IdempotencyOutcome.EXECUTED, reexecuted.outcome(),
                "过期键必须按新请求处理");
        assertEquals("V3", reexecuted.value());
        assertEquals(2, sideEffects.get(), "过期后允许重新执行一次");
        log.info("[test-evict] TTL 场景通过：evictedEarly={} evicted={} sideEffects={}",
                evictedEarly, evicted, sideEffects.get());
    }

    @Test
    @DisplayName("容量上限：超过上限时从最老的终态记录开始淘汰，未被淘汰的键仍可重放")
    void capacityEvictsOldestTerminalFirst() throws Exception {
        properties.setMaxEntries(2);
        exec("k-cap-1", "req-cap-1", "V1");
        Thread.sleep(5); // 保证 createdAtMs 可区分先后
        exec("k-cap-2", "req-cap-2", "V2");
        Thread.sleep(5);
        exec("k-cap-3", "req-cap-3", "V3");
        assertEquals(3, store.size());

        int evicted = cleaner.cleanup(System.currentTimeMillis());
        log.info("[test-evict] 容量淘汰 evicted={} remaining={}", evicted, store.size());
        assertEquals(1, evicted, "只淘汰超出容量的部分");
        assertEquals(2, store.size());

        // 最老的 k-cap-1 被淘汰 -> 重新执行；其余两个键仍然重放
        IdempotencyResult<String> oldest = exec("k-cap-1", "req-cap-1b", "V1-NEW");
        assertEquals(IdempotencyOutcome.EXECUTED, oldest.outcome());
        assertEquals(IdempotencyOutcome.REPLAYED, exec("k-cap-2", "req-cap-2b", "V2").outcome());
        assertEquals(IdempotencyOutcome.REPLAYED, exec("k-cap-3", "req-cap-3b", "V3").outcome());
        assertEquals(4, sideEffects.get(), "只有被淘汰的键重新执行");
    }

    @Test
    @DisplayName("处理中的记录永不被清理：即使已过期，清理也跳过 IN_PROGRESS，并发同键仍得到进行中结论")
    void inProgressRecordsAreNeverEvicted() throws Exception {
        properties.setTtlSeconds(1); // 极短 TTL：记录很快“过期”
        CyclicBarrier insideAction = new CyclicBarrier(2);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<IdempotencyResult<String>> holder = pool.submit(() ->
                    service.execute("k-inflight", "fp-k-inflight", "req-hold", () -> {
                        try {
                            insideAction.await(5, TimeUnit.SECONDS); // 已进入业务
                            insideAction.await(5, TimeUnit.SECONDS); // 等待清理与并发请求
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        sideEffects.incrementAndGet();
                        return "V-HOLD";
                    }));
            insideAction.await(5, TimeUnit.SECONDS); // 确认记录处于 IN_PROGRESS

            // 清理时间远超 TTL，但处理中的记录不得被当作过期删除
            int evicted = cleaner.cleanup(System.currentTimeMillis() + 3_600_000);
            assertEquals(0, evicted, "处理中的记录不得被清理");
            assertEquals(IdempotencyStatus.IN_PROGRESS, store.get("k-inflight").status());

            // 并发同键请求：仍得到“进行中”，而不是被当成新请求重复执行
            IdempotencyResult<String> concurrent = service.execute(
                    "k-inflight", "fp-k-inflight", "req-concurrent", () -> {
                        sideEffects.incrementAndGet();
                        return "SHOULD-NOT-RUN";
                    });
            log.info("[test-evict] 清理后并发同键 outcome={}", concurrent.outcome());
            assertEquals(IdempotencyOutcome.IN_PROGRESS, concurrent.outcome());

            insideAction.await(5, TimeUnit.SECONDS); // 放行业务完成
            IdempotencyResult<String> done = holder.get(10, TimeUnit.SECONDS);
            assertEquals("V-HOLD", done.value());
            assertEquals(IdempotencyStatus.SUCCEEDED, store.get("k-inflight").status(),
                    "完成后状态必须正常落终态，不因清理丢失");
            assertEquals(1, sideEffects.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("清理与同键并发提交同时发生：副作用恰好一次，无状态丢失")
    void cleanupRacingWithSameKeySubmissions() throws Exception {
        properties.setTtlSeconds(600); // 长 TTL：清理并发进行但不淘汰任何记录
        int threads = 16;
        String key = "k-race";
        CyclicBarrier barrier = new CyclicBarrier(threads + 1);
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        try {
            // 清理线程：与提交同时反复执行清理
            Future<?> cleanerFuture = pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < 50; i++) {
                        cleaner.cleanup(System.currentTimeMillis());
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            List<Future<IdempotencyResult<String>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String requestId = "req-race-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return service.execute(key, "fp-" + key, requestId, () -> {
                        try {
                            Thread.sleep(50); // 拉开处理窗口，让清理穿插其中
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        sideEffects.incrementAndGet();
                        return "V-RACE";
                    });
                }));
            }
            int executed = 0;
            for (Future<IdempotencyResult<String>> f : futures) {
                IdempotencyResult<String> r = f.get(15, TimeUnit.SECONDS);
                log.info("[test-evict] race outcome={}", r.outcome());
                if (r.outcome() == IdempotencyOutcome.EXECUTED) {
                    executed++;
                }
            }
            cleanerFuture.get(15, TimeUnit.SECONDS);
            assertEquals(1, executed, "清理并发进行时同键也只能执行一次");
            assertEquals(1, sideEffects.get());
            assertEquals(IdempotencyStatus.SUCCEEDED, store.get(key).status(),
                    "记录状态不得在清理中丢失");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("容量清理与多键并发提交同时发生：每个键恰好执行一次，最终规模不超上限")
    void capacityCleanupRacingWithManyKeys() throws Exception {
        properties.setMaxEntries(5);
        int keys = 20;
        ConcurrentHashMap<String, AtomicInteger> executionsPerKey = new ConcurrentHashMap<>();
        CyclicBarrier barrier = new CyclicBarrier(keys + 1);
        ExecutorService pool = Executors.newFixedThreadPool(keys + 1);
        try {
            Future<?> cleanerFuture = pool.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < 100; i++) {
                        cleaner.cleanup(System.currentTimeMillis());
                        Thread.sleep(1);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < keys; i++) {
                String key = "k-many-" + i;
                executionsPerKey.put(key, new AtomicInteger());
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    IdempotencyResult<String> r = service.execute(key, "fp-" + key,
                            "req-" + key, () -> {
                                executionsPerKey.get(key).incrementAndGet();
                                sideEffects.incrementAndGet();
                                return "V-" + key;
                            });
                    log.info("[test-evict] key={} outcome={}", key, r.outcome());
                    assertEquals(IdempotencyOutcome.EXECUTED, r.outcome(),
                            "每个键只提交一次，必须获得执行权（清理不得干扰）");
                    assertNull(r.errorCode());
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            cleanerFuture.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        executionsPerKey.forEach((key, count) ->
                assertEquals(1, count.get(), "键 " + key + " 必须恰好执行一次，清理不得导致重复或丢失"));
        assertEquals(keys, sideEffects.get());

        int finalEvict = cleaner.cleanup(System.currentTimeMillis());
        log.info("[test-evict] 多键并发结束 finalEvict={} finalSize={} sideEffects={}",
                finalEvict, store.size(), sideEffects.get());
        assertTrue(store.size() <= 5, "清理后规模必须回落到容量上限以内，实际 " + store.size());
    }
}
