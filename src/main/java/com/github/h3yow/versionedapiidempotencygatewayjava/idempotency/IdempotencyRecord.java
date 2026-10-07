package com.github.h3yow.versionedapiidempotencygatewayjava.idempotency;

/**
 * 一个幂等键对应的不可变记录（进程内）。
 *
 * <p>状态机：{@link IdempotencyStatus#IN_PROGRESS} -> SUCCEEDED / FAILED；
 * FAILED 允许同键带同一请求负载重试一次。
 *
 * <p>保留与过期：
 * <ul>
 *     <li>{@code createdAtMs}：占位（IN_PROGRESS 建立）时间；</li>
 *     <li>{@code completedAtMs}：进入终态（SUCCEEDED/FAILED）的时间；</li>
 *     <li>TTL 只对终态记录、从完成时刻起算；IN_PROGRESS 记录永不过期、绝不被清理，
 *     否则并发请求会拿到错误结论。</li>
 * </ul>
 *
 * @param status             当前状态
 * @param requestFingerprint 请求负载指纹，用于检测键冲突
 * @param value              成功结果（仅 SUCCEEDED 有值）
 * @param errorCode          失败错误编码（仅 FAILED 有值）
 * @param errorMessage       失败错误信息
 * @param ownerRequestId     首次持有该键执行的请求标识（可审计）
 * @param createdAtMs        占位建立时间（epoch ms）
 * @param completedAtMs      进入终态时间（epoch ms）；IN_PROGRESS 时为 0
 */
public record IdempotencyRecord(
        IdempotencyStatus status,
        String requestFingerprint,
        Object value,
        String errorCode,
        String errorMessage,
        String ownerRequestId,
        long createdAtMs,
        long completedAtMs) {

    public static IdempotencyRecord inProgress(String fingerprint, String requestId) {
        return inProgress(fingerprint, requestId, System.currentTimeMillis());
    }

    public static IdempotencyRecord inProgress(String fingerprint, String requestId, long nowMs) {
        return new IdempotencyRecord(IdempotencyStatus.IN_PROGRESS, fingerprint,
                null, null, null, requestId, nowMs, 0L);
    }

    public IdempotencyRecord succeeded(Object result) {
        return succeeded(result, System.currentTimeMillis());
    }

    public IdempotencyRecord succeeded(Object result, long nowMs) {
        return new IdempotencyRecord(IdempotencyStatus.SUCCEEDED, requestFingerprint,
                result, null, null, ownerRequestId, createdAtMs, nowMs);
    }

    public IdempotencyRecord failed(String code, String message) {
        return failed(code, message, System.currentTimeMillis());
    }

    public IdempotencyRecord failed(String code, String message, long nowMs) {
        return new IdempotencyRecord(IdempotencyStatus.FAILED, requestFingerprint,
                null, code, message, ownerRequestId, createdAtMs, nowMs);
    }

    /** 是否终态记录（可被 TTL/容量策略清理）。 */
    public boolean isTerminal() {
        return status != IdempotencyStatus.IN_PROGRESS;
    }

    /**
     * 在给定时刻是否已过存活时间。仅终态记录可能过期；
     * {@code ttlMs <= 0} 表示关闭 TTL。
     */
    public boolean isExpired(long nowMs, long ttlMs) {
        return ttlMs > 0 && isTerminal() && completedAtMs > 0L
                && nowMs - completedAtMs >= ttlMs;
    }

    /** 容量淘汰排序时间：终态按完成时间，处理中按建立时间。 */
    public long evictionTimestamp() {
        return completedAtMs > 0L ? completedAtMs : createdAtMs;
    }
}
