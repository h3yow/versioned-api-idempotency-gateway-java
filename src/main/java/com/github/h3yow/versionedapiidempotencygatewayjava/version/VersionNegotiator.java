package com.github.h3yow.versionedapiidempotencygatewayjava.version;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiVersion;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ErrorCode;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.RequestContext;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 版本协商器：根据客户端声明版本选择兼容处理路径。
 *
 * <p>协商规则（结论稳定、可区分）：
 * <ol>
 *     <li>缺失 X-Api-Version -> {@link ErrorCode#VERSION_MISSING}；</li>
 *     <li>版本格式无法解析，或该主版本系列对本能力完全未知 -> {@link ErrorCode#VERSION_UNKNOWN}；</li>
 *     <li>主版本系列已知，但该主版本已整体退役（跨主版本不兼容）
 *         -> {@link ErrorCode#VERSION_INCOMPATIBLE}；</li>
 *     <li>精确命中：直接选用该版本；已弃用版本附带弃用与日落信号但仍服务；</li>
 *     <li>同主版本内未精确命中（次版本未单独登记）：不直接拒绝，回落到该主版本内
 *         最高的可服务注册版本，即该主版本的兼容处理路径。</li>
 * </ol>
 */
@Component
public class VersionNegotiator {

    private final VersionRegistry registry;

    public VersionNegotiator(VersionRegistry registry) {
        this.registry = registry;
    }

    public VersionDecision negotiate(String capability, RequestContext context) {
        String raw = context.declaredVersion();
        if (raw == null || raw.isBlank()) {
            return reject(null, ErrorCode.VERSION_MISSING,
                    "缺少 X-Api-Version 请求头，客户端必须声明期望版本");
        }

        final ApiVersion requested;
        try {
            requested = ApiVersion.parse(raw);
        } catch (IllegalArgumentException ex) {
            return reject(null, ErrorCode.VERSION_UNKNOWN,
                    "无法识别的版本声明 '" + raw + "'，期望格式 major.minor（如 2.0）");
        }

        List<VersionDescriptor> sameMajor = registry.findByMajor(capability, requested.major());
        if (sameMajor.isEmpty()) {
            return reject(requested, ErrorCode.VERSION_UNKNOWN,
                    "能力 '" + capability + "' 不存在主版本系列 " + requested.major()
                            + "，请使用受支持的主版本");
        }

        VersionDescriptor exact = registry.find(capability, requested);
        if (exact != null) {
            if (exact.status() == VersionStatus.UNSUPPORTED) {
                return reject(requested, ErrorCode.VERSION_INCOMPATIBLE,
                        "版本 " + requested.asString() + " 已停止服务；主版本之间互不兼容，请升级客户端");
            }
            return accept(exact, requested,
                    "精确命中注册版本 " + exact.version().asString());
        }

        // 同主版本兼容：未单独登记的次版本不直接拒绝，落到该主版本内最高的可服务注册版本。
        // 这保证同主版本内“声明一个没有单独登记的次版本”也有确定的兼容处理路径；
        // 主版本是否可服务由上一步（是否存在非退役版本）判定，跨主版本不兼容仍稳定拒绝。
        List<VersionDescriptor> serviceable = sameMajor.stream()
                .filter(d -> d.status() != VersionStatus.UNSUPPORTED)
                .toList();
        if (serviceable.isEmpty()) {
            return reject(requested, ErrorCode.VERSION_INCOMPATIBLE,
                    "主版本 " + requested.major() + " 已整体停止服务；主版本之间互不兼容，请升级客户端");
        }
        VersionDescriptor fallback = serviceable.stream()
                .max(Comparator.comparing(VersionDescriptor::version))
                .orElseThrow();
        String relation = fallback.version().minor() >= requested.minor()
                ? "同主版本向前兼容："
                : "同主版本兼容落点（声明次版本高于已注册，回落到该主版本受支持路径）：";
        return accept(fallback, requested,
                relation + requested.asString() + " -> " + fallback.version().asString());
    }

    private VersionDecision accept(VersionDescriptor descriptor, ApiVersion requested, String reason) {
        return new VersionDecision(true, requested, descriptor.version(),
                descriptor.status(), null, reason, descriptor.sunsetNotice());
    }

    private VersionDecision reject(ApiVersion requested, ErrorCode code, String reason) {
        return new VersionDecision(false, requested, null, null, code, reason, null);
    }
}
