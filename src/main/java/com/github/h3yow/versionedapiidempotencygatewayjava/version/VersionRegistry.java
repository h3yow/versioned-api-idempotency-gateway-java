package com.github.h3yow.versionedapiidempotencygatewayjava.version;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiVersion;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 本地版本注册表：按能力登记受支持/已弃用/不再支持的版本。
 *
 * <p>orders 能力登记：
 * <ul>
 *     <li>0.1 已退役（UNSUPPORTED）：已知主版本但跨主版本不兼容；</li>
 *     <li>1.0 已弃用（DEPRECATED）但仍可服务，附带弃用/日落提示；</li>
 *     <li>1.2 受支持（SUPPORTED）：旧主版本客户端不受影响；</li>
 *     <li>2.0 / 3.0 受支持。</li>
 * </ul>
 */
@Component
public class VersionRegistry {

    /**
     * 合规的 Sunset 头内容（RFC 8594 日期 + 迁移提示）。只使用 ASCII：
     * 真实 Servlet 容器（Tomcat）会拒绝/丢弃含非 ASCII 字符的响应头值。
     */
    private static final String SUNSET_1_0 =
            "Wed, 30 Jun 2027 00:00:00 GMT; migrate to 2.x or 3.x (same capability: orders)";

    private final ConcurrentMap<String, NavigableMap<ApiVersion, VersionDescriptor>> tables =
            new ConcurrentHashMap<>();

    @PostConstruct
    void seed() {
        register(new VersionDescriptor("orders", new ApiVersion(0, 1),
                VersionStatus.UNSUPPORTED, "主版本 0 已停止服务"));
        register(new VersionDescriptor("orders", new ApiVersion(1, 0),
                VersionStatus.DEPRECATED, SUNSET_1_0));
        register(new VersionDescriptor("orders", new ApiVersion(1, 2),
                VersionStatus.SUPPORTED, null));
        register(new VersionDescriptor("orders", new ApiVersion(2, 0),
                VersionStatus.SUPPORTED, null));
        register(new VersionDescriptor("orders", new ApiVersion(3, 0),
                VersionStatus.SUPPORTED, null));
    }

    public void register(VersionDescriptor descriptor) {
        tables.computeIfAbsent(descriptor.capability(), c -> new TreeMap<>(Comparator.naturalOrder()))
                .put(descriptor.version(), descriptor);
    }

    public List<VersionDescriptor> descriptors(String capability) {
        return List.copyOf(table(capability).values());
    }

    public VersionDescriptor find(String capability, ApiVersion version) {
        return table(capability).get(version);
    }

    /**
     * 返回某主版本下状态不早于给定状态的全部描述（按版本升序）。
     */
    public List<VersionDescriptor> findByMajor(String capability, int major) {
        return table(capability).entrySet().stream()
                .filter(e -> e.getKey().major() == major)
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(VersionDescriptor::version))
                .toList();
    }

    private NavigableMap<ApiVersion, VersionDescriptor> table(String capability) {
        return tables.getOrDefault(capability, new TreeMap<>());
    }
}
