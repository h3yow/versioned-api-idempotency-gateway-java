package com.github.h3yow.versionedapiidempotencygatewayjava.idem;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等键存储：负责键级互斥与记录生命周期管理。
 * 同一键的并发请求只会有一个进入临界区执行业务。
 */
public interface IdempotencyStore {

    /**
     * 在键锁内检查/创建记录并执行业务。
     * 返回 NEW（首次执行）、REPLAY_SUCCEEDED、IN_PROGRESS、
     * RETRY_AFTER_FAILURE、KEY_CONFLICT 之一。
     */
    IdempotencyRecordOutcome executeOnce(String key, String requestFingerprint, Supplier<Object> action);

    /** 审计/测试辅助：当前已记录的键数量。 */
    int size();

    /** 测试辅助：读取某个键的当前状态。 */
    Optional<IdempotencyRecord> peek(String key);
}
