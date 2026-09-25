package com.github.h3yow.versionedapiidempotencygatewayjava.idempotency;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 进程内幂等记录存储（线程安全）。
 *
 * <p>每个幂等键持有一把独立的 {@link ReentrantLock}：同一键的判定与状态迁移
 * 串行化，不同键互不阻塞；记录本身不可变，状态迁移以 CAS 式替换完成，
 * 杜绝结果覆盖与状态丢失。
 */
@Component
public class IdempotencyStore {

    private final ConcurrentMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 获取某键的串行锁（调用方负责在 finally 中 unlock）。 */
    public ReentrantLock lockFor(String key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    public IdempotencyRecord get(String key) {
        return records.get(key);
    }

    /** 原子地仅在键不存在时放入 IN_PROGRESS 记录；返回已存在的记录或 null。 */
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

    public int size() {
        return records.size();
    }

    public void clear() {
        records.clear();
    }
}
