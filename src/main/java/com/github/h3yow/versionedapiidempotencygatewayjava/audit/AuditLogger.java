package com.github.h3yow.versionedapiidempotencygatewayjava.audit;

import com.github.h3yow.versionedapiidempotencygatewayjava.model.AuditEvent;

import java.util.List;

/** 审计日志：记录请求标识、版本决策、幂等结果、权限判定依据。 */
public interface AuditLogger {

    void log(AuditEvent event);

    /** 测试辅助：读取已记录事件。 */
    List<AuditEvent> events();
}
