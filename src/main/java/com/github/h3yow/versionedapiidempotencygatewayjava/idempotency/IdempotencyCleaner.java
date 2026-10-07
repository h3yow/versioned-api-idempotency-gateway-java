package com.github.h3yow.versionedapiidempotencygatewayjava.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 幂等记录清理器：按存活时间（TTL）与容量上限做有界保留。
 *
 * <p>规则：
 * <ul>
 *     <li>只清理终态（SUCCEEDED/FAILED）记录；处理中（IN_PROGRESS）记录一律跳过，
 *         避免把正在执行的键误删导致重复执行或错误结论；</li>
 *     <li>TTL：记录年龄（now - createdAtMs）超过配置存活时间即过期，
 *         过期键被移除后按新请求处理、允许重新执行；</li>
 *     <li>容量：记录数超过上限时，从最老的终态记录开始淘汰，直到回落到上限以内；</li>
 *     <li>并发安全：每次淘汰都在该键的串行锁内重新读取当前记录并再次校验
 *         （仍是终态、仍满足过期/淘汰条件），随后以 CAS 式 remove 删除；
 *         与并发提交/状态迁移互斥，不会删错版本、不会造成状态丢失。</li>
 * </ul>
 */
@Component
public class IdempotencyCleaner {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleaner.class);

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;

    public IdempotencyCleaner(IdempotencyStore store, IdempotencyProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /** 后台周期清理（间隔可配置）；测试可直接调用 {@link #cleanup(long)} 传入受控时间。 */
    @Scheduled(fixedDelayString = "${gateway.idempotency.cleanup-interval-ms:60000}",
            initialDelayString = "${gateway.idempotency.cleanup-interval-ms:60000}")
    public void scheduledCleanup() {
        int evicted = cleanup(System.currentTimeMillis());
        if (evicted > 0) {
            log.info("[idempotency-cleanup] evicted={} remaining={}", evicted, store.size());
        }
    }

    /**
     * 执行一轮清理：先按 TTL 过期，再按容量上限淘汰；返回本轮淘汰的记录数。
     */
    public int cleanup(long nowMs) {
        int evicted = 0;
        long ttlMs = properties.ttlSeconds() > 0 ? properties.ttlSeconds() * 1000 : 0;

        // —— 阶段一：按存活时间过期 ——
        if (ttlMs > 0) {
            for (Map.Entry<String, IdempotencyRecord> entry : store.entries()) {
                IdempotencyRecord snapshot = entry.getValue();
                if (!snapshot.isTerminal() || nowMs - snapshot.createdAtMs() < ttlMs) {
                    continue;
                }
                if (evictIfStillTerminal(entry.getKey(),
                        r -> nowMs - r.createdAtMs() >= ttlMs)) {
                    evicted++;
                }
            }
        }

        // —— 阶段二：按容量上限淘汰最老的终态记录 ——
        int maxEntries = properties.maxEntries();
        if (maxEntries > 0 && store.size() > maxEntries) {
            List<Map.Entry<String, IdempotencyRecord>> terminalOldestFirst = new ArrayList<>(
                    store.entries().stream()
                            .filter(e -> e.getValue().isTerminal())
                            .sorted(Comparator.comparingLong(e -> e.getValue().createdAtMs()))
                            .toList());
            for (Map.Entry<String, IdempotencyRecord> entry : terminalOldestFirst) {
                if (store.size() <= maxEntries) {
                    break;
                }
                if (evictIfStillTerminal(entry.getKey(), r -> true)) {
                    evicted++;
                }
            }
        }
        return evicted;
    }

    /**
     * 在键锁内重新读取并校验后删除：仅当当前记录仍是终态且满足淘汰条件时才移除。
     * 与 {@link IdempotencyService} 的占位/状态迁移在同一把键锁上互斥。
     */
    private boolean evictIfStillTerminal(String key,
                                         java.util.function.Predicate<IdempotencyRecord> condition) {
        ReentrantLock lock = store.lockFor(key);
        lock.lock();
        try {
            IdempotencyRecord current = store.get(key);
            if (current == null || !current.isTerminal() || !condition.test(current)) {
                return false;
            }
            boolean removed = store.remove(key, current);
            if (removed) {
                log.info("[idempotency-cleanup] evicted key={} status={} ageMs={}",
                        key, current.status(),
                        System.currentTimeMillis() - current.createdAtMs());
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }
}
