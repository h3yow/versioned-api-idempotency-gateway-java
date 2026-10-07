package com.github.h3yow.versionedapiidempotencygatewayjava.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 幂等记录有界保留的定时维护：按存活时间（TTL）与容量上限清理终态记录。
 *
 * <p>清理本身完全委托 {@link IdempotencyStore#purge(long)}：逐键在其条带锁内
 * “二次确认 + 条件删除”，所以与并发提交同时发生时：
 * <ul>
 *     <li>不会把同一键执行两次（只有终态记录才可能被删，执行权占位不受影响）；</li>
 *     <li>不会覆盖结果或丢失状态（条件删除要求仍是同一引用）；</li>
 *     <li>正在处理中的记录绝不删除，避免调用方拿到错误结论。</li>
 * </ul>
 * TTL 关闭或容量不设限时，对应清理自动成为空操作。
 */
@Component
public class IdempotencyMaintenance {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyMaintenance.class);

    private final IdempotencyStore store;
    private final long ttlMs;
    private final int maxRecords;

    public IdempotencyMaintenance(
            IdempotencyStore store,
            @Value("${gateway.idempotency.ttl-ms:3600000}") long ttlMs,
            @Value("${gateway.idempotency.max-records:10000}") int maxRecords) {
        this.store = store;
        this.ttlMs = ttlMs;
        this.maxRecords = maxRecords;
    }

    /** 定时清理过期与超额终态记录（间隔可配置）。 */
    @Scheduled(fixedDelayString = "${gateway.idempotency.cleanup-interval-ms:60000}")
    public void purge() {
        IdempotencyStore.PurgeOutcome outcome = store.purge(System.currentTimeMillis());
        if (outcome.expiredEvicted() > 0 || outcome.capacityEvicted() > 0) {
            log.info("[idempotency-maintenance] ttlMs={} maxRecords={} expiredEvicted={} "
                            + "capacityEvicted={} remaining={}",
                    ttlMs, maxRecords, outcome.expiredEvicted(),
                    outcome.capacityEvicted(), outcome.remaining());
        }
    }
}
