package com.github.h3yow.versionedapiidempotencygatewayjava.tests.idempotency;

import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyOutcome;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyResult;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyService;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等记录有界保留测试：TTL 过期、容量淘汰，以及清理与并发提交同时发生时的正确性。
 */
class IdempotencyRetentionTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRetentionTest.class);

    private record Harness(IdempotencyStore store, IdempotencyService service, AtomicLong clock) {
    }

    private Harness harness(long ttlMs, int maxRecords) {
        IdempotencyStore store = new IdempotencyStore().configure(ttlMs, maxRecords);
        AtomicLong clock = new AtomicLong(1_000_000L);
        IdempotencyService service = new IdempotencyService(store, clock::get);
        return new Harness(store, service, clock);
    }

    private void log(String scenario, Harness h, Object... kv) {
        StringBuilder sb = new StringBuilder("[test-retention] 场景=").append(scenario)
                .append(" size=").append(h.store().size());
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(' ').append(kv[i]).append('=').append(kv[i + 1]);
        }
        log.info(sb.toString());
    }

    @Test
    @DisplayName("TTL：终态记录过期后同键按新请求处理，可重新执行；未过期则照常 REPLAYED")
    void expiredRecordIsTreatedAsNew() {
        Harness h = harness(100, 0);
        AtomicInteger effects = new AtomicInteger();

        IdempotencyResult<String> first = h.service().execute("k-ttl", "fp", "r1", () -> {
            effects.incrementAndGet();
            return "V1";
        });
        assertEquals(IdempotencyOutcome.EXECUTED, first.outcome());

        h.clock().addAndGet(50);
        IdempotencyResult<String> withinTtl = h.service().execute("k-ttl", "fp", "r2", () -> {
            effects.incrementAndGet();
            return "V2";
        });
        assertEquals(IdempotencyOutcome.REPLAYED, withinTtl.outcome(), "TTL 内仍应重放旧结果");
        assertEquals("V1", withinTtl.value());
        assertEquals(1, effects.get());

        h.clock().addAndGet(60); // 累计 110ms > ttl=100
        IdempotencyResult<String> afterTtl = h.service().execute("k-ttl", "fp", "r3", () -> {
            effects.incrementAndGet();
            return "V2";
        });
        log("TTL过期重新执行", h, "outcome", afterTtl.outcome(), "value", afterTtl.value());
        assertEquals(IdempotencyOutcome.EXECUTED, afterTtl.outcome(),
                "过期键必须被当成新请求处理（结论为 EXECUTED）");
        assertEquals("V2", afterTtl.value());
        assertEquals(2, effects.get(), "过期后业务真实地再执行一次");
    }

    @Test
    @DisplayName("TTL：过期后允许同一键携带不同负载重新执行（不再视为键冲突）")
    void expiredRecordAllowsDifferentPayload() {
        Harness h = harness(100, 0);
        h.service().execute("k-swap", "fp-A", "r1", () -> "A");
        h.clock().addAndGet(101);
        IdempotencyResult<String> re = h.service().execute("k-swap", "fp-B", "r2", () -> "B");
        log("TTL过期换负载", h, "outcome", re.outcome());
        assertEquals(IdempotencyOutcome.EXECUTED, re.outcome());
        assertEquals("B", re.value());
    }

    @Test
    @DisplayName("TTL：失败终态过期后可重新执行；IN_PROGRESS 记录绝不过期、不被误删")
    void inProgressNeverExpired() throws Exception {
        Harness h = harness(100, 0);
        AtomicInteger effects = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> h.service().execute("k-hold", "fp", "r-hold", () -> {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                effects.incrementAndGet();
                return "HELD";
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            // 让“时间”远超 TTL：处理中记录依然不得被任何清理路径删除
            h.clock().addAndGet(100_000);
            var outcome = h.store().purge(h.clock().get());
            log("处理中记录抗清理", h, "purgeExpired", outcome.expiredEvicted());
            assertEquals(0, outcome.expiredEvicted(), "IN_PROGRESS 记录绝不被 TTL 清理");
            assertEquals(1, h.store().size(), "处理中记录必须仍然在位");

            // 并发同键请求必须观察到 IN_PROGRESS，而不是因“记录消失”而二次执行
            IdempotencyResult<String> concurrent = h.service().execute(
                    "k-hold", "fp", "r-other", () -> {
                        effects.incrementAndGet();
                        return "SHOULD-NOT-RUN";
                    });
            assertEquals(IdempotencyOutcome.IN_PROGRESS, concurrent.outcome());

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertEquals(1, effects.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("容量：超过上限时淘汰最旧终态记录，处理中记录不被淘汰；被淘汰键可重新执行")
    void capacityEvictsOldestTerminalOnly() {
        Harness h = harness(0, 2);
        AtomicInteger effects = new AtomicInteger();

        h.service().execute("k1", "fp1", "r1", () -> { effects.incrementAndGet(); return "O1"; });
        h.clock().addAndGet(10);
        h.service().execute("k2", "fp2", "r2", () -> { effects.incrementAndGet(); return "O2"; });
        h.clock().addAndGet(10);
        // 第三条触发容量维护：最旧的 k1 应被淘汰
        IdempotencyResult<String> third = h.service().execute("k3", "fp3", "r3", () -> {
            effects.incrementAndGet();
            return "O3";
        });
        assertEquals(IdempotencyOutcome.EXECUTED, third.outcome());
        assertTrue(h.store().size() <= 2, "清理后记录数不得超过容量上限，实际=" + h.store().size());
        assertFalse(h.service().execute("k2", "fp2", "r4", () -> {
            effects.incrementAndGet(); return "O2-NEW";
        }).outcome().equals(IdempotencyOutcome.EXECUTED), "较新的 k2 不应被淘汰");

        // 被淘汰的 k1 现在是新键，可重新执行
        IdempotencyResult<String> k1Again = h.service().execute("k1", "fp1", "r5", () -> {
            effects.incrementAndGet();
            return "O1-NEW";
        });
        log("容量淘汰最旧", h, "k1Again", k1Again.outcome());
        assertEquals(IdempotencyOutcome.EXECUTED, k1Again.outcome(), "被容量淘汰的键应能重新执行");
    }

    @Test
    @DisplayName("并发正确性：TTL 清理与同键并发提交同时发生，业务恰好执行一次、其余 IN_PROGRESS/REPLAYED")
    void purgeConcurrentWithSubmissions() throws Exception {
        int threads = 16;
        Harness h = harness(100, 0);
        AtomicInteger effects = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            // 预置一个“即将过期”的成功记录
            h.service().execute("k-race", "fp", "r-seed", () -> {
                effects.incrementAndGet();
                return "SEED";
            });
            h.clock().addAndGet(101); // 已过期

            List<Future<IdempotencyResult<String>>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    // 每个线程都“边清理边提交”，制造清理与占位的强竞争
                    h.store().purge(h.clock().get());
                    return h.service().execute("k-race", "fp", "r-race-" + idx, () -> {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        effects.incrementAndGet();
                        return "RACED";
                    });
                }));
            }
            int executed = 0, inProgress = 0, replayed = 0;
            for (Future<IdempotencyResult<String>> f : futures) {
                IdempotencyResult<String> r = f.get(10, TimeUnit.SECONDS);
                switch (r.outcome()) {
                    case EXECUTED -> executed++;
                    case IN_PROGRESS -> inProgress++;
                    case REPLAYED -> replayed++;
                    default -> throw new AssertionError("意外结论: " + r.outcome());
                }
            }
            log("清理与并发提交", h, "executed", executed, "inProgress", inProgress,
                    "replayed", replayed, "副作用总数", effects.get());
            // 旧记录已过期：在 16 个并发者中，清理+占位合起来只能让恰好一个线程重新执行业务
            assertEquals(1, executed, "过期键重新执行也必须恰好一次");
            assertEquals(2, effects.get(), "预置 1 次 + 过期后重新执行 1 次");
            assertEquals(threads - 1, inProgress + replayed, "其余并发请求不得执行业务");

            // 全部结束后再提交：必须重放最终结果，绝无第三次执行
            IdempotencyResult<String> after = h.service().execute(
                    "k-race", "fp", "r-after", () -> {
                        effects.incrementAndGet();
                        return "OVERWRITE";
                    });
            assertEquals(IdempotencyOutcome.REPLAYED, after.outcome());
            assertEquals("RACED", after.value(), "结果不得被覆盖");
            assertEquals(2, effects.get(), "状态不丢失：后续只重放，不产生第三次副作用");
        } finally {
            pool.shutdownNow();
        }
    }
}
