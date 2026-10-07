package com.github.h3yow.versionedapiidempotencygatewayjava.idempotency;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 进程内幂等记录存储（线程安全），支持有界保留与过期清理。
 *
 * <p>并发模型：
 * <ul>
 *     <li>使用固定数量的条带锁（striped locks）串行化同一键的判定与状态迁移；
 *     条带数量固定，锁集合天然有界，且不存在“删除键锁后新旧两把锁并存”的竞态；
 *     不同键落在同一条带时串行，不影响正确性；</li>
 *     <li>记录不可变，状态迁移以 CAS 式替换完成，杜绝结果覆盖与状态丢失；</li>
 *     <li>过期/容量清理一律在对应条带锁内“二次确认 + 条件删除”：
 *     仅当记录仍是同一引用且仍满足过期条件时才删除，因此与并发提交互不破坏；</li>
 *     <li>IN_PROGRESS 记录永远不参与 TTL 与容量淘汰，避免处理中记录被误删
 *     而让调用方拿到错误结论。</li>
 * </ul>
 */
@Component
public class IdempotencyStore {

    /** 条带锁数量：固定有界；同一键恒定映射到同一条带。 */
    static final int STRIPE_COUNT = 256;

    private final ConcurrentMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private final ReentrantLock[] stripes;

    /** 终态记录存活时间（ms）；<=0 表示关闭 TTL。 */
    private volatile long ttlMs;
    /** 终态记录容量上限；<=0 表示不限。 */
    private volatile int maxRecords;

    /** 测试/直连默认：TTL 关闭、容量不限，保持“只进不出”的原始行为可选。 */
    public IdempotencyStore() {
        this(0L, 0);
    }

    @Autowired
    public IdempotencyStore(
            @Value("${gateway.idempotency.ttl-ms:3600000}") long ttlMs,
            @Value("${gateway.idempotency.max-records:10000}") int maxRecords) {
        this.stripes = new ReentrantLock[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            this.stripes[i] = new ReentrantLock();
        }
        this.ttlMs = ttlMs;
        this.maxRecords = maxRecords;
    }

    public long ttlMs() {
        return ttlMs;
    }

    public int maxRecords() {
        return maxRecords;
    }

    public IdempotencyStore configure(long ttlMs, int maxRecords) {
        this.ttlMs = ttlMs;
        this.maxRecords = maxRecords;
        return this;
    }

    /** 获取某键的串行条带锁（调用方负责在 finally 中 unlock）。 */
    public ReentrantLock lockFor(String key) {
        int index = (key.hashCode() & 0x7fffffff) % STRIPE_COUNT;
        return stripes[index];
    }

    public IdempotencyRecord get(String key) {
        return records.get(key);
    }

    /** 原子地仅在键不存在时放入记录；返回已存在的记录或 null。 */
    public IdempotencyRecord putIfAbsent(String key, IdempotencyRecord record) {
        return records.putIfAbsent(key, record);
    }

    /** 仅当当前记录与期望记录为同一引用时才替换（CAS 语义）。 */
    public boolean compareAndReplace(String key, IdempotencyRecord expected, IdempotencyRecord next) {
        return records.replace(key, expected, next);
    }

    /** 移除记录（失败后重试前清空旧终态）。 */
    public void remove(String key, IdempotencyRecord expected) {
        records.remove(key, expected);
    }

    /**
     * 惰性过期淘汰：调用方持有该键条带锁。仅当当前记录仍是同一引用、
     * 仍为终态且确实已过 TTL 时才删除。
     *
     * @return true 表示已删除（调用方应把该键当成新请求处理）
     */
    public boolean evictIfExpired(String key, IdempotencyRecord expected, long nowMs) {
        if (ttlMs <= 0 || expected == null || !expected.isTerminal()) {
            return false;
        }
        IdempotencyRecord current = records.get(key);
        if (current != expected || !current.isExpired(nowMs, ttlMs)) {
            return false;
        }
        return records.remove(key, expected);
    }

    /**
     * 执行一轮有界保留清理：先淘汰过 TTL 的终态记录，再按容量上限淘汰最旧终态记录。
     * 每个键的删除都在其条带锁内二次确认；IN_PROGRESS 记录绝不删除。
     *
     * @return 清理结果计数（用于日志与测试断言）
     */
    public PurgeOutcome purge(long nowMs) {
        int expiredEvicted = purgeExpired(nowMs);
        int capacityEvicted = enforceCapacity();
        return new PurgeOutcome(expiredEvicted, capacityEvicted, records.size());
    }

    private int purgeExpired(long nowMs) {
        if (ttlMs <= 0) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<String, IdempotencyRecord> entry : records.entrySet()) {
            String key = entry.getKey();
            IdempotencyRecord seen = entry.getValue();
            if (!seen.isTerminal() || !seen.isExpired(nowMs, ttlMs)) {
                continue;
            }
            ReentrantLock lock = lockFor(key);
            lock.lock();
            try {
                if (evictIfExpired(key, seen, nowMs)) {
                    removed++;
                }
            } finally {
                lock.unlock();
            }
        }
        return removed;
    }

    private int enforceCapacity() {
        int cap = maxRecords;
        if (cap <= 0 || records.size() <= cap) {
            return 0;
        }
        // 仅在终态记录中按“完成时间（处理中兜底建立时间）”挑最旧；处理中不参与。
        List<Map.Entry<String, IdempotencyRecord>> terminals = new ArrayList<>();
        for (Map.Entry<String, IdempotencyRecord> e : records.entrySet()) {
            if (e.getValue().isTerminal()) {
                terminals.add(e);
            }
        }
        int excess = records.size() - cap;
        if (excess <= 0 || terminals.isEmpty()) {
            return 0;
        }
        terminals.sort(Comparator
                .comparingLong((Map.Entry<String, IdempotencyRecord> e) -> e.getValue().evictionTimestamp())
                .thenComparing(Map.Entry::getKey));

        int removed = 0;
        for (Map.Entry<String, IdempotencyRecord> candidate : terminals) {
            if (removed >= excess || records.size() <= cap) {
                break;
            }
            String key = candidate.getKey();
            IdempotencyRecord seen = candidate.getValue();
            ReentrantLock lock = lockFor(key);
            lock.lock();
            try {
                // 二次确认：仍是同一终态引用才删；处理中/已变更记录跳过。
                if (records.remove(key, seen) && seen.isTerminal()) {
                    removed++;
                }
            } finally {
                lock.unlock();
            }
        }
        return removed;
    }

    public int size() {
        return records.size();
    }

    /**
     * 是否需要做一轮全量维护（容量已超上限）。TTL 的全量扫描交由定时任务；
     * 到达单个过期键由 {@link #evictIfExpired} 惰性处理，因此写路径无需每次全表扫描。
     */
    public boolean requiresMaintenance() {
        return maxRecords > 0 && records.size() > maxRecords;
    }

    public void clear() {
        records.clear();
    }

    /** 一轮清理的计数结果。 */
    public record PurgeOutcome(int expiredEvicted, int capacityEvicted, int remaining) {
    }
}
