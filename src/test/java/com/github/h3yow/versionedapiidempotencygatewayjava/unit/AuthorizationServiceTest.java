package com.github.h3yow.versionedapiidempotencygatewayjava.tests.unit;

import com.github.h3yow.versionedapiidempotencygatewayjava.auth.AuthorizationDecision;
import com.github.h3yow.versionedapiidempotencygatewayjava.auth.AuthorizationService;
import com.github.h3yow.versionedapiidempotencygatewayjava.auth.InMemoryClientDirectory;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.ClientCapability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 权限判定：放行 / 身份缺失 / 未知客户端 / 能力不足，判定依据可审计且可区分。 */
class AuthorizationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationServiceTest.class);

    private final AuthorizationService service =
            new AuthorizationService(new InMemoryClientDirectory());

    private void print(String caseName, AuthorizationDecision d) {
        log.info("[权限判定] case={} => allowed={}, clientId={}, capability={}, reason={}",
                caseName, d.allowed(), d.clientId(), d.capability(), d.reason());
    }

    @Test
    @DisplayName("具备能力的客户端放行")
    void granted() {
        AuthorizationDecision d = service.authorize("client-a", ClientCapability.ORDER_CREATE);
        print("client-a", d);
        assertTrue(d.allowed());
    }

    @Test
    @DisplayName("未提供客户端标识：拒绝，依据为 missing client identity")
    void missingIdentity() {
        AuthorizationDecision d = service.authorize(null, ClientCapability.ORDER_CREATE);
        print("anonymous", d);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("missing client identity"));
    }

    @Test
    @DisplayName("未注册客户端：拒绝，依据为 unknown client")
    void unknownClient() {
        AuthorizationDecision d = service.authorize("client-unknown", ClientCapability.ORDER_CREATE);
        print("client-unknown", d);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("unknown client"));
    }

    @Test
    @DisplayName("已注册但能力不足：拒绝，依据为 capability not granted")
    void capabilityNotGranted() {
        AuthorizationDecision d = service.authorize("client-b", ClientCapability.ORDER_CREATE);
        print("client-b", d);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("capability not granted"));
    }
}
