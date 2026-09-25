package com.github.h3yow.versionedapiidempotencygatewayjava.version;

import com.github.h3yow.versionedapiidempotencygatewayjava.model.ApiVersion;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.VersionDecision;

import java.util.Map;

/** 已注册版本表（声明哪些版本受支持/弃用）。 */
public interface VersionRegistry {

    /** 原始版本字符串 -> 决策；raw 为 null/空白时返回 MISSING。 */
    VersionDecision negotiate(String rawVersion);

    /** 查询某主版本对应的处理路径。 */
    VersionedHandler handlerFor(ApiVersion version);

    /** 审计用：当前注册表快照。 */
    Map<String, String> snapshot();
}
