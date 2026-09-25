package com.github.h3yow.versionedapiidempotencygatewayjava.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 审计日志：输出结构化日志并保留进程内审计轨迹。
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);
    private final List<AuditEntry> entries = new CopyOnWriteArrayList<>();

    public void record(AuditEntry entry) {
        entries.add(entry);
        log.info("[audit] requestId={} clientId={} capability={} declaredVersion={} "
                        + "versionDecision='{}' idempotencyOutcome={} authDecision='{}' result={}",
                entry.requestId(),
                entry.clientId(),
                entry.capability(),
                entry.declaredVersion(),
                entry.versionDecision(),
                entry.idempotencyOutcome(),
                entry.authDecision(),
                entry.result());
    }

    public List<AuditEntry> entries() {
        return List.copyOf(entries);
    }

    public void clear() {
        entries.clear();
    }
}
