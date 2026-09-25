package com.github.h3yow.versionedapiidempotencygatewayjava.version;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiVersion;

/**
 * 某个能力的一个注册版本描述。
 */
public record VersionDescriptor(
        String capability,
        ApiVersion version,
        VersionStatus status,
        String sunsetNotice) {
}
