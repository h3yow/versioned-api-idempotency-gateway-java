package com.github.h3yow.versionedapiidempotencygatewayjava.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 幂等记录保留策略配置：存活时间、容量上限与清理周期。
 */
@Component
@ConfigurationProperties(prefix = "gateway.idempotency")
public class IdempotencyProperties {

    /** 记录存活时间（秒）；0 表示不按时间过期。 */
    private long ttlSeconds = 0;

    /** 记录容量上限；0 表示不限制容量。 */
    private int maxEntries = 0;

    /** 后台清理周期（毫秒）。 */
    private long cleanupIntervalMs = 60_000;

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public int maxEntries() {
        return maxEntries;
    }

    public long cleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
    }
}
