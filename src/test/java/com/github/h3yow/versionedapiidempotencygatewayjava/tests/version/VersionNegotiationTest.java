package com.github.h3yow.versionedapiidempotencygatewayjava.tests.version;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.ErrorCode;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.RequestContext;
import com.github.h3yow.versionedapiidempotencygatewayjava.version.VersionDecision;
import com.github.h3yow.versionedapiidempotencygatewayjava.version.VersionNegotiator;
import com.github.h3yow.versionedapiidempotencygatewayjava.version.VersionRegistry;
import com.github.h3yow.versionedapiidempotencygatewayjava.version.VersionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本协商测试：缺失/未知/跨主版本/弃用/旧版本兼容。
 */
class VersionNegotiationTest {

    private static final Logger log = LoggerFactory.getLogger(VersionNegotiationTest.class);

    private VersionNegotiator negotiator;

    @BeforeEach
    void setUp() throws Exception {
        VersionRegistry registry = new VersionRegistry();
        Method seed = VersionRegistry.class.getDeclaredMethod("seed");
        seed.setAccessible(true);
        seed.invoke(registry);
        negotiator = new VersionNegotiator(registry);
    }

    private VersionDecision decide(String declaredVersion) {
        RequestContext ctx = new RequestContext("req-version-" + declaredVersion,
                declaredVersion, "client-mobile-1", null);
        VersionDecision decision = negotiator.negotiate("orders", ctx);
        log.info("[test-version] requestId={} declared={} accepted={} selected={} "
                        + "status={} rejection={} reason='{}'",
                ctx.requestId(), declaredVersion, decision.accepted(),
                decision.selected() == null ? "-" : decision.selected().asString(),
                decision.selectedStatus(),
                decision.rejectionCode() == null ? "-" : decision.rejectionCode().name(),
                decision.reason());
        return decision;
    }

    @Test
    @DisplayName("缺失版本 -> VERSION_MISSING，与其他拒绝原因可区分")
    void missingVersionRejected() {
        VersionDecision d = decide(null);
        assertFalse(d.accepted());
        assertEquals(ErrorCode.VERSION_MISSING, d.rejectionCode());
        assertNull(d.selected());
    }

    @Test
    @DisplayName("格式无法解析 -> VERSION_UNKNOWN")
    void malformedVersionRejected() {
        VersionDecision d = decide("v2");
        assertFalse(d.accepted());
        assertEquals(ErrorCode.VERSION_UNKNOWN, d.rejectionCode());
    }

    @Test
    @DisplayName("未知主版本系列 -> VERSION_UNKNOWN")
    void unknownMajorRejected() {
        VersionDecision d = decide("9.0");
        assertFalse(d.accepted());
        assertEquals(ErrorCode.VERSION_UNKNOWN, d.rejectionCode());
    }

    @Test
    @DisplayName("已整体退役的主版本 -> VERSION_INCOMPATIBLE（跨主版本不兼容）")
    void retiredMajorIncompatible() {
        VersionDecision d = decide("0.1");
        assertFalse(d.accepted());
        assertEquals(ErrorCode.VERSION_INCOMPATIBLE, d.rejectionCode());
    }

    @Test
    @DisplayName("精确命中已弃用版本 1.0：仍接受，但返回 DEPRECATED 状态与日落提示")
    void deprecatedVersionStillServedWithSignal() {
        VersionDecision d = decide("1.0");
        assertTrue(d.accepted());
        assertEquals("1.0", d.selected().asString());
        assertEquals(VersionStatus.DEPRECATED, d.selectedStatus());
        assertNotNull(d.sunset());
        log.info("[test-version] 弃用信号 sunset='{}'", d.sunset());
    }

    @Test
    @DisplayName("受支持的旧主版本客户端（1.2）不受影响，正常接受")
    void supportedOldClientUnaffected() {
        VersionDecision d = decide("1.2");
        assertTrue(d.accepted());
        assertEquals("1.2", d.selected().asString());
        assertEquals(VersionStatus.SUPPORTED, d.selectedStatus());
        assertNull(d.sunset());
    }

    @Test
    @DisplayName("当前主版本 2.0 / 3.0 正常接受")
    void currentVersionsAccepted() {
        assertTrue(decide("2.0").accepted());
        assertTrue(decide("3.0").accepted());
    }

    @Test
    @DisplayName("三类版本拒绝编码两两不同，错误契约稳定可区分")
    void rejectionCodesAreDistinct() {
        assertEquals(ErrorCode.VERSION_MISSING, decide(null).rejectionCode());
        assertEquals(ErrorCode.VERSION_UNKNOWN, decide("9.0").rejectionCode());
        assertEquals(ErrorCode.VERSION_INCOMPATIBLE, decide("0.1").rejectionCode());
        assertEquals(400, ErrorCode.VERSION_MISSING.httpStatus());
        assertEquals(404, ErrorCode.VERSION_UNKNOWN.httpStatus());
        assertEquals(400, ErrorCode.VERSION_INCOMPATIBLE.httpStatus());
    }
}
