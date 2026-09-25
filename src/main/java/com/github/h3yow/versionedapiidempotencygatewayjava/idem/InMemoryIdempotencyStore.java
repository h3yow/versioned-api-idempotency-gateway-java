package com.github.h3yow.versionedapiidempotencygatewayjava.idem;

import com.github.h3yow.versionedapiidempotencygatewayjava.model.IdempotencyStatus;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 基于 {@link ConcurrentHashMap} + 键级锁的本地幂等存储。
 *
 * <p>状态机（同一幂等键）：
 * <pre>
 *   (无记录) ──首次请求──────────► IN_PROGRESS ──成功──► SUCCEEDED（结果冻结）
 *                                    │  ▲                ▲
 *                                    └──┤失败            │同键同请求体
 *                                       ▼                │
 *                                     FAILED ──重试──────┘（RETRY_AFTER_FAILURE）
 * </pre>
 *
 * <p>关键并发约束（"锁内决策、锁外执行、锁内提交"）：
 * <ul>
 *   <li>记录的检查与创建在键锁内完成，保证同一键只有一个请求成为执行者；</li>
 *   <li>业务动作在键锁外执行：执行期间并发请求进入键锁后看到 IN_PROGRESS，
 *       立即得到可区分结果，不阻塞等待，也绝不重复执行；</li>
 *   <li>终态（SUCCEEDED/FAILED）在动作结束后重新进入键锁提交，
 *       保证结果不会被覆盖或丢失；</li>
 *   <li>SUCCEEDED 记录为冻结结果，同键重复提交直接回放；</li>
 *   <li>同键但请求指纹不同（无论对方处于何种状态）→ KEY_CONFLICT，原记录不受影响。</li>
 * </ul>
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentHashMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Override
    public IdempotencyRecordOutcome executeOnce(String key, String requestFingerprint, Supplier<Object> action) {
        Object lock = locks.computeIfAbsent(key, k -> new Object());

        // 第一阶段：键锁内决策
        IdempotencyRecord record;
        IdempotencyStatus runStatus = null;

        synchronized (lock) {
            record = records.get(key);
            if (record == null) {
                record = new IdempotencyRecord(IdempotencyRecord.State.IN_PROGRESS, requestFingerprint);
                records.put(key, record);
                runStatus = IdempotencyStatus.NEW;
            } else if (!requestFingerprint.equals(record.getRequestFingerprint())) {
                return new IdempotencyRecordOutcome(IdempotencyStatus.KEY_CONFLICT, record);
            } else {
                switch (record.getState()) {
                    case IN_PROGRESS -> {
                        return new IdempotencyRecordOutcome(IdempotencyStatus.IN_PROGRESS, record);
                    }
                    case SUCCEEDED -> {
                        return new IdempotencyRecordOutcome(IdempotencyStatus.REPLAY_SUCCEEDED, record);
                    }
                    case FAILED -> {
                        // 失败后允许同请求体重试：重新置为进行中，随后锁外再执行一次
                        record.setState(IdempotencyRecord.State.IN_PROGRESS);
                        record.setFailure(null);
                        runStatus = IdempotencyStatus.RETRY_AFTER_FAILURE;
                    }
                    default -> throw new IllegalStateException("unknown state: " + record.getState());
                }
            }
        }

        // 第二阶段：键锁外执行业务动作，使并发者可观测 IN_PROGRESS
        return runAction(lock, record, action, runStatus);
    }

    /**
     * 业务动作在键锁之外执行；执行结束后重新进入键锁提交终态，
     * 保证结果不会被覆盖或丢失。
     */
    private IdempotencyRecordOutcome runAction(Object lock, IdempotencyRecord record,
                                               Supplier<Object> action, IdempotencyStatus successStatus) {
        try {
            Object result = action.get();
            synchronized (lock) {
                record.setResult(result);
                record.setFailure(null);
                record.setState(IdempotencyRecord.State.SUCCEEDED);
                return new IdempotencyRecordOutcome(successStatus, record);
            }
        } catch (RuntimeException | Error e) {
            synchronized (lock) {
                record.setFailure(e);
                record.setState(IdempotencyRecord.State.FAILED);
            }
            throw e;
        }
    }

    @Override
    public int size() {
        return records.size();
    }

    @Override
    public Optional<IdempotencyRecord> peek(String key) {
        return Optional.ofNullable(records.get(key));
    }
}
