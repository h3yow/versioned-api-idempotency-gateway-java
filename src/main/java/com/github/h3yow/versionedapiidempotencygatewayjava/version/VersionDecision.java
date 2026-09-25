package com.github.h3yow.versionedapiidempotencygatewayjava.version;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiVersion;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ErrorCode;

/**
 * 版本协商结论：是否接受、选中的处理版本、拒绝原因与弃用信号。
 */
public record VersionDecision(
        boolean accepted,
        ApiVersion requested,
        ApiVersion selected,
        VersionStatus selectedStatus,
        ErrorCode rejectionCode,
        String reason,
        String sunset) {
}
