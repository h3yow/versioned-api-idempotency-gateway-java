package com.github.h3yow.versionedapiidempotencygatewayjava.version;

import com.github.h3yow.versionedapiidempotencygatewayjava.model.ApiVersion;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.VersionDecision;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.VersionStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 基于内存配置的版本注册表。
 *
 * <p>协商规则（错误稳定且可区分）：
 * <ul>
 *   <li>头缺失/空白 → {@link VersionStatus#MISSING}</li>
 *   <li>无法解析的字符串 → {@link VersionStatus#UNKNOWN}</li>
 *   <li>可解析但该主版本下没有此已发布次版本 → {@link VersionStatus#UNKNOWN}</li>
 *   <li>主版本无任何处理路径（跨主版本不兼容）→ {@link VersionStatus#INCOMPATIBLE}</li>
 *   <li>命中已发布版本：返回 SUPPORTED 或 DEPRECATED（仍可正常调用）</li>
 * </ul>
 */
public class InMemoryVersionRegistry implements VersionRegistry {

    /** 一条已发布版本记录。 */
    public record Release(ApiVersion version, VersionStatus status, String notice) {
    }

    private static final List<Release> RELEASES = List.of(
            new Release(new ApiVersion(1, 0), VersionStatus.SUPPORTED, null),
            new Release(new ApiVersion(1, 9), VersionStatus.DEPRECATED,
                    "deprecated; please upgrade to 2.0 (sunset 2027-03-01)"),
            new Release(new ApiVersion(2, 0), VersionStatus.SUPPORTED, null)
    );

    private final NavigableMap<String, Release> releasesByKey = new TreeMap<>();
    private final Map<Integer, VersionedHandler> handlersByMajor = new TreeMap<>();

    public InMemoryVersionRegistry(List<VersionedHandler> handlers) {
        for (Release release : RELEASES) {
            releasesByKey.put(release.version().toString(), release);
        }
        for (VersionedHandler handler : handlers) {
            VersionedHandler previous = handlersByMajor.put(handler.supportedMajor(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "duplicate handler for major version " + handler.supportedMajor());
            }
        }
    }

    @Override
    public VersionDecision negotiate(String rawVersion) {
        if (rawVersion == null || rawVersion.isBlank()) {
            return new VersionDecision(VersionStatus.MISSING, null, null, false,
                    "X-Api-Version header is missing");
        }

        final ApiVersion requested;
        try {
            requested = ApiVersion.parse(rawVersion);
        } catch (IllegalArgumentException e) {
            return new VersionDecision(VersionStatus.UNKNOWN, null, null, false,
                    "version '" + rawVersion + "' cannot be parsed");
        }

        Release release = releasesByKey.get(requested.toString());
        if (release != null) {
            boolean deprecated = release.status() == VersionStatus.DEPRECATED;
            return new VersionDecision(release.status(), requested, requested, deprecated,
                    release.notice() != null ? release.notice() : "exact release match");
        }

        // 主版本存在但次版本未发布 → 未知版本
        boolean majorKnown = RELEASES.stream().anyMatch(r -> r.version().major() == requested.major());
        if (majorKnown) {
            return new VersionDecision(VersionStatus.UNKNOWN, requested, null, false,
                    "version '" + requested + "' is not a released version of major "
                            + requested.major());
        }

        // 主版本完全不受支持 → 跨主版本不兼容
        String supportedMajors = RELEASES.stream()
                .map(r -> String.valueOf(r.version().major()))
                .distinct()
                .collect(Collectors.joining(","));
        return new VersionDecision(VersionStatus.INCOMPATIBLE, requested, null, false,
                "major version " + requested.major() + " is incompatible; supported majors: ["
                        + supportedMajors + "]");
    }

    @Override
    public VersionedHandler handlerFor(ApiVersion version) {
        VersionedHandler handler = handlersByMajor.get(version.major());
        if (handler == null) {
            throw new IllegalStateException("no handler registered for major " + version.major());
        }
        return handler;
    }

    @Override
    public Map<String, String> snapshot() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (Release release : RELEASES) {
            snapshot.put(release.version().toString(),
                    release.status() + (release.notice() != null ? " (" + release.notice() + ")" : ""));
        }
        return snapshot;
    }
}
