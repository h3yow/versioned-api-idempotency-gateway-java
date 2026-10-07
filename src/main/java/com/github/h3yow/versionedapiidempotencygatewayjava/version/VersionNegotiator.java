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
 *         或声明的 minor 无任何已注册路径 -> {@link ErrorCode#VERSION_INCOMPATIBLE}
 *         / {@link ErrorCode#VERSION_UNKNOWN}（见具体分支）；</li>
 *     <li>精确命中：直接选用该版本；已弃用版本附带弃用与日落信号但仍服务；</li>
 *     <li>同主版本内未精确命中：向前兼容选择 minor 不小于声明值的最高注册版本。</li>
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

        // 同主版本内未精确命中：落到主版本内兼容的处理方式，不直接拒绝。
        // 优先选择 minor 不高于声明值的最高注册版本（向后兼容：客户端能处理其子集）；
        // 若声明值低于所有注册版本，则选择 minor 高于声明值的最低注册版本（向前兼容）。
        List<VersionDescriptor> serviceable = sameMajor.stream()
                .filter(d -> d.status() != VersionStatus.UNSUPPORTED)
                .toList();
        if (serviceable.isEmpty()) {
            return reject(requested, ErrorCode.VERSION_INCOMPATIBLE,
                    "主版本 " + requested.major() + " 已整体停止服务；主版本之间互不兼容，请升级客户端");
        }
        VersionDescriptor backward = serviceable.stream()
                .filter(d -> d.version().minor() <= requested.minor())
                .max(Comparator.comparing(VersionDescriptor::version))
                .orElse(null);
        if (backward != null) {
            return accept(backward, requested,
                    "同主版本向后兼容：" + requested.asString() + " -> "
                            + backward.version().asString() + "（未单独登记的次版本落到主版本内兼容处理）");
        }
        VersionDescriptor forward = serviceable.stream()
                .filter(d -> d.version().minor() > requested.minor())
                .min(Comparator.comparing(VersionDescriptor::version))
                .orElse(null);
        if (forward == null) {
            // 防御性分支：serviceable 非空且未精确命中时必然存在兼容落点
            return reject(requested, ErrorCode.VERSION_UNKNOWN,
                    "版本 " + requested.asString() + " 未在服务端注册，且主版本 "
                            + requested.major() + " 内无兼容路径");
        }
        return accept(forward, requested,
                "同主版本向前兼容：" + requested.asString() + " -> "
                        + forward.version().asString() + "（未单独登记的次版本落到主版本内兼容处理）");
    }

    private VersionDecision accept(VersionDescriptor descriptor, ApiVersion requested, String reason) {
        return new VersionDecision(true, requested, descriptor.version(),
                descriptor.status(), null, reason, descriptor.sunsetNotice());
    }

    private VersionDecision reject(ApiVersion requested, ErrorCode code, String reason) {
        return new VersionDecision(false, requested, null, null, code, reason, null);
    }
}
