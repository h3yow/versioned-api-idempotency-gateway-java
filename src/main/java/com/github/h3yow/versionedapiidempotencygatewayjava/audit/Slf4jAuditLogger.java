package com.github.h3yow.versionedapiidempotencygatewayjava.audit;

import com.github.h3yow.versionedapiidempotencygatewayjava.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 审计日志：每次调用同时写入 SLF4J（结构化文本）和进程内缓冲（供测试断言/打印）。
 * 字段固定包含：请求标识、客户端、能力、版本决策、幂等状态、权限判定、最终结果。
 */
@Component
public class Slf4jAuditLogger implements AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("AUDIT");
    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void log(AuditEvent event) {
        events.add(event);
        log.info("audit requestId={} clientId={} capability={} versionHeader='{}' "
                        + "versionDecision=[{}] idempotency=[{}] authorization=[{}] outcome={}",
                event.requestId(),
                event.clientId(),
                event.capability(),
                event.rawVersion(),
                event.versionDecision(),
                event.idempotencyStatus(),
                event.authorization(),
                event.outcome());
    }

    @Override
    public List<AuditEvent> events() {
        return List.copyOf(events);
    }
}
