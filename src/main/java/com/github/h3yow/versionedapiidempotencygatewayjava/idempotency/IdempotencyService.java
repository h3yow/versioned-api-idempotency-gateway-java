package com.github.h3yow.versionedapiidempotencygatewayjava.idempotency;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiException;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 幂等门面：保证同一键并发仅执行一次业务副作用，并区分各类重试语义。
 *
 * <p>语义约定：
 * <ul>
 *     <li>{@link IdempotencyOutcome#EXECUTED}：首次获得执行权，业务动作真实执行一次；</li>
 *     <li>{@link IdempotencyOutcome#REPLAYED}：已有成功记录，返回与首次一致的结果，无副作用；</li>
 *     <li>{@link IdempotencyOutcome#IN_PROGRESS}：同键请求正在处理中，调用方应稍后重试；</li>
 *     <li>{@link IdempotencyOutcome#RETRY_AFTER_FAILURE}：上次执行失败，同键同负载允许重试；</li>
 *     <li>{@link IdempotencyOutcome#KEY_CONFLICT}：同一幂等键携带不同请求负载，拒绝执行。</li>
 * </ul>
 *
 * <p>并发约束：每键一把锁，判定/状态迁移在键锁内串行；业务动作在锁外执行，
 * 终态以 CAS 写入，因此并发下不重复执行、不覆盖结果、不丢失状态。
 */
@Component
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyStore store;

    public IdempotencyService(IdempotencyStore store) {
        this.store = store;
    }

    public <T> IdempotencyResult<T> execute(
            String key, String fingerprint, String requestId, Supplier<T> action) {
        var lock = store.lockFor(key);

        // ---- 阶段一：在键锁内判定身份并占位 ----
        lock.lock();
        boolean ownsExecution;
        IdempotencyRecord inProgress;
        IdempotencyOutcome outcome;
        try {
            IdempotencyRecord existing = store.get(key);
            if (existing == null) {
                inProgress = IdempotencyRecord.inProgress(fingerprint, requestId);
                IdempotencyRecord raced = store.putIfAbsent(key, inProgress);
                ownsExecution = raced == null;
                outcome = IdempotencyOutcome.EXECUTED;
            } else {
                if (!existing.requestFingerprint().equals(fingerprint)) {
                    return new IdempotencyResult<>(IdempotencyOutcome.KEY_CONFLICT, null,
                            ErrorCode.IDEMPOTENCY_KEY_CONFLICT.name(),
                            "幂等键 '" + key + "' 已用于不同的请求负载（首次请求 "
                                    + existing.ownerRequestId() + "），禁止复用");
                }
                switch (existing.status()) {
                    case IN_PROGRESS -> {
                        return new IdempotencyResult<>(IdempotencyOutcome.IN_PROGRESS, null,
                                ErrorCode.IDEMPOTENCY_IN_PROGRESS.name(),
                                "幂等键 '" + key + "' 的请求仍在处理中（持有请求 "
                                        + existing.ownerRequestId() + "），请稍后重试");
                    }
                    case SUCCEEDED -> {
                        @SuppressWarnings("unchecked")
                        T cached = (T) existing.value();
                        return new IdempotencyResult<>(IdempotencyOutcome.REPLAYED, cached, null, null);
                    }
                    case FAILED -> {
                        // 同键同负载：清空失败终态并由本次请求重新占位执行
                        store.remove(key, existing);
                        inProgress = IdempotencyRecord.inProgress(fingerprint, requestId);
                        IdempotencyRecord raced = store.putIfAbsent(key, inProgress);
                        ownsExecution = raced == null;
                        outcome = IdempotencyOutcome.RETRY_AFTER_FAILURE;
                    }
                    default -> throw new IllegalStateException("unknown status");
                }
            }
        } finally {
            lock.unlock();
        }

        if (!ownsExecution) {
            // 极端竞态兜底：占位被其他线程抢先，按进行中处理
            return new IdempotencyResult<>(IdempotencyOutcome.IN_PROGRESS, null,
                    ErrorCode.IDEMPOTENCY_IN_PROGRESS.name(),
                    "幂等键 '" + key + "' 的请求仍在处理中，请稍后重试");
        }

        // ---- 阶段二：锁外执行业务动作（同一键只可能有一个线程到达这里）----
        log.info("[idempotency] key={} requestId={} outcome={} business-execution-started",
                key, requestId, outcome);
        T value;
        IdempotencyRecord terminal;
        IdempotencyResult<T> result;
        try {
            value = action.get();
            lock.lock();
            try {
                terminal = inProgress.succeeded(value);
                store.compareAndReplace(key, inProgress, terminal);
            } finally {
                lock.unlock();
            }
            result = new IdempotencyResult<>(outcome, value, null, null);
        } catch (RuntimeException ex) {
            String code = (ex instanceof ApiException apiEx)
                    ? apiEx.errorCode().name() : ErrorCode.BUSINESS_ERROR.name();
            lock.lock();
            try {
                terminal = inProgress.failed(code, ex.getMessage());
                store.compareAndReplace(key, inProgress, terminal);
            } finally {
                lock.unlock();
            }
            log.warn("[idempotency] key={} requestId={} execution-failed code={}",
                    key, requestId, code);
            // 不抛出：由编排层依据失败结论映射统一错误契约
            result = new IdempotencyResult<>(outcome, null, code, ex.getMessage());
        }
        log.info("[idempotency] key={} requestId={} final-status={} outcome={}",
                key, requestId, terminal.status(), outcome);
        return result;
    }
}
