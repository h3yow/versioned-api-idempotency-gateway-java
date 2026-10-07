package com.github.h3yow.versionedapiidempotencygatewayjava.idempotency;

/**
 * 一个幂等键对应的不可变记录（进程内）。
 *
 * <p>状态机：{@link IdempotencyStatus#IN_PROGRESS} -> SUCCEEDED / FAILED；
 * FAILED 允许同键带同一请求负载重试一次。
 *
 * <p>{@code createdAtMs} 是记录首次占位的时间，用于存活时间（TTL）过期判定；
 * 状态迁移不改变该时间，记录的“年龄”从占位起算。
 *
 * @param status             当前状态
 * @param requestFingerprint 请求负载指纹，用于检测键冲突
 * @param value              成功结果（仅 SUCCEEDED 有值）
 * @param errorCode          失败错误编码（仅 FAILED 有值）
 * @param errorMessage       失败错误信息
 * @param ownerRequestId     首次持有该键执行的请求标识（可审计）
 * @param createdAtMs        记录首次占位的毫秒时间戳（过期判定基准）
 */
public record IdempotencyRecord(
        IdempotencyStatus status,
        String requestFingerprint,
        Object value,
        String errorCode,
        String errorMessage,
        String ownerRequestId,
        long createdAtMs) {

    public static IdempotencyRecord inProgress(String fingerprint, String requestId) {
        return new IdempotencyRecord(IdempotencyStatus.IN_PROGRESS, fingerprint,
                null, null, null, requestId, System.currentTimeMillis());
    }

    public IdempotencyRecord succeeded(Object result) {
        return new IdempotencyRecord(IdempotencyStatus.SUCCEEDED, requestFingerprint,
                result, null, null, ownerRequestId, createdAtMs);
    }

    public IdempotencyRecord failed(String code, String message) {
        return new IdempotencyRecord(IdempotencyStatus.FAILED, requestFingerprint,
                null, code, message, ownerRequestId, createdAtMs);
    }

    /** 是否处于终态（SUCCEEDED/FAILED）；只有终态记录才允许被清理。 */
    public boolean isTerminal() {
        return status == IdempotencyStatus.SUCCEEDED || status == IdempotencyStatus.FAILED;
    }
}
