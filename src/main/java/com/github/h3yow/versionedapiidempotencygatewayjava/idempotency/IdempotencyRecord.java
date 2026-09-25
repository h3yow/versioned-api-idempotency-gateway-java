package com.github.h3yow.versionedapiidempotencygatewayjava.idempotency;

/**
 * 一个幂等键对应的不可变记录（进程内）。
 *
 * <p>状态机：{@link IdempotencyStatus#IN_PROGRESS} -> SUCCEEDED / FAILED；
 * FAILED 允许同键带同一请求负载重试一次。
 *
 * @param status             当前状态
 * @param requestFingerprint 请求负载指纹，用于检测键冲突
 * @param value              成功结果（仅 SUCCEEDED 有值）
 * @param errorCode          失败错误编码（仅 FAILED 有值）
 * @param errorMessage       失败错误信息
 * @param ownerRequestId     首次持有该键执行的请求标识（可审计）
 */
public record IdempotencyRecord(
        IdempotencyStatus status,
        String requestFingerprint,
        Object value,
        String errorCode,
        String errorMessage,
        String ownerRequestId) {

    public static IdempotencyRecord inProgress(String fingerprint, String requestId) {
        return new IdempotencyRecord(IdempotencyStatus.IN_PROGRESS, fingerprint,
                null, null, null, requestId);
    }

    public IdempotencyRecord succeeded(Object result) {
        return new IdempotencyRecord(IdempotencyStatus.SUCCEEDED, requestFingerprint,
                result, null, null, ownerRequestId);
    }

    public IdempotencyRecord failed(String code, String message) {
        return new IdempotencyRecord(IdempotencyStatus.FAILED, requestFingerprint,
                null, code, message, ownerRequestId);
    }
}
