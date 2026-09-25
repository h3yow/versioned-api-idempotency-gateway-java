package com.github.h3yow.versionedapiidempotencygatewayjava.tests.unit;

import com.github.h3yow.versionedapiidempotencygatewayjava.model.VersionDecision;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.VersionStatus;
import com.github.h3yow.versionedapiidempotencygatewayjava.version.InMemoryVersionRegistry;
import com.github.h3yow.versionedapiidempotencygatewayjava.version.VersionedHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 版本协商规则：缺失 / 未知 / 跨主版本不兼容 / 弃用信号，彼此稳定可区分。 */
class VersionNegotiationTest {

    private static final Logger log = LoggerFactory.getLogger(VersionNegotiationTest.class);

    private final InMemoryVersionRegistry registry = new InMemoryVersionRegistry(List.of(
            stubHandler(1), stubHandler(2)));

    private static VersionedHandler stubHandler(int major) {
        return new VersionedHandler() {
            @Override
            public int supportedMajor() {
                return major;
            }

            @Override
            public Object handle(Object request, String requestId) {
                return null;
            }
        };
    }

    private void printDecision(String caseName, VersionDecision d) {
        log.info("[版本协商] case={} => status={}, requested={}, resolved={}, deprecated={}, reason={}",
                caseName, d.status(), d.requested(), d.resolved(), d.deprecated(), d.reason());
    }

    @Test
    @DisplayName("受支持旧版本 1.0 正常协商，不受弃用版本影响")
    void supportedOldVersion() {
        VersionDecision d = registry.negotiate("1.0");
        printDecision("1.0", d);
        assertEquals(VersionStatus.SUPPORTED, d.status());
        assertFalse(d.deprecated());
        assertEquals("1.0", d.resolved().toString());
    }

    @Test
    @DisplayName("当前版本 2.0 正常协商")
    void currentVersion() {
        VersionDecision d = registry.negotiate("2.0");
        printDecision("2.0", d);
        assertEquals(VersionStatus.SUPPORTED, d.status());
        assertFalse(d.deprecated());
    }

    @Test
    @DisplayName("已弃用版本 1.9 返回 DEPRECATED 明确信号但仍可用")
    void deprecatedVersion() {
        VersionDecision d = registry.negotiate("1.9");
        printDecision("1.9", d);
        assertEquals(VersionStatus.DEPRECATED, d.status());
        assertTrue(d.deprecated());
        assertEquals("1.9", d.resolved().toString());
        assertTrue(d.reason().contains("deprecated"));
    }

    @Test
    @DisplayName("缺失版本头 => MISSING")
    void missingVersion() {
        VersionDecision d = registry.negotiate(null);
        printDecision("null", d);
        assertEquals(VersionStatus.MISSING, d.status());

        VersionDecision blank = registry.negotiate("   ");
        printDecision("blank", blank);
        assertEquals(VersionStatus.MISSING, blank.status());
    }

    @Test
    @DisplayName("格式非法版本 => UNKNOWN")
    void malformedVersion() {
        VersionDecision d = registry.negotiate("not-a-version");
        printDecision("malformed", d);
        assertEquals(VersionStatus.UNKNOWN, d.status());
    }

    @Test
    @DisplayName("可解析但未发布的次版本 => UNKNOWN")
    void unreleasedMinorVersion() {
        VersionDecision d = registry.negotiate("1.5");
        printDecision("1.5", d);
        assertEquals(VersionStatus.UNKNOWN, d.status());
    }

    @Test
    @DisplayName("跨主版本不兼容 => INCOMPATIBLE，与 UNKNOWN 可区分")
    void incompatibleMajorVersion() {
        VersionDecision d = registry.negotiate("3.0");
        printDecision("3.0", d);
        assertEquals(VersionStatus.INCOMPATIBLE, d.status());
        assertTrue(d.reason().contains("incompatible"));
    }

    @Test
    @DisplayName("快照中弃用与受支持版本均可审计")
    void snapshotIsAuditable() {
        String snapshot = registry.snapshot().toString();
        log.info("[版本协商] registry snapshot={}", snapshot);
        assertTrue(snapshot.contains("SUPPORTED"));
        assertTrue(snapshot.contains("DEPRECATED"));
    }
}
