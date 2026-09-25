package com.github.h3yow.versionedapiidempotencygatewayjava.web;

import com.github.h3yow.versionedapiidempotencygatewayjava.audit.AuditLogger;
import com.github.h3yow.versionedapiidempotencygatewayjava.auth.AuthorizationDecision;
import com.github.h3yow.versionedapiidempotencygatewayjava.auth.AuthorizationService;
import com.github.h3yow.versionedapiidempotencygatewayjava.exception.ErrorType;
import com.github.h3yow.versionedapiidempotencygatewayjava.exception.GatewayException;
import com.github.h3yow.versionedapiidempotencygatewayjava.idem.IdempotencyRecordOutcome;
import com.github.h3yow.versionedapiidempotencygatewayjava.idem.IdempotencyStore;
import com.github.h3yow.versionedapiidempotencygatewayjava.idem.RequestFingerprinter;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.AuditEvent;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.ClientCapability;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.GatewayResponse;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.IdempotencyStatus;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.VersionDecision;
import com.github.h3yow.versionedapiidempotencygatewayjava.model.VersionStatus;
import com.github.h3yow.versionedapiidempotencygatewayjava.version.VersionedHandler;
import com.github.h3yow.versionedapiidempotencygatewayjava.version.VersionRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 默认门面：所有调用入口共用的一致处理顺序。
 *
 * <pre>
 *   1. 版本协商：缺失/未知/不兼容直接拒绝（错误可区分），弃用仅发信号不拒绝
 *   2. 权限判定：拒绝时对外统一映射为 RESOURCE_NOT_FOUND，不泄露资源是否存在
 *   3. 幂等执行：同键并发只执行一次业务；区分 进行中/回放/失败重试/键冲突
 *   4. 审计：每个出口（含失败）都记录请求标识、版本决策、幂等状态、权限依据
 * </pre>
 */
@Component
public class DefaultGatewayFacade implements GatewayFacade {

    private final VersionRegistry registry;
    private final AuthorizationService authorizationService;
    private final IdempotencyStore idempotencyStore;
    private final RequestFingerprinter fingerprinter;
    private final AuditLogger auditLogger;

    public DefaultGatewayFacade(VersionRegistry registry,
                                AuthorizationService authorizationService,
                                IdempotencyStore idempotencyStore,
                                RequestFingerprinter fingerprinter,
                                AuditLogger auditLogger) {
        this.registry = registry;
        this.authorizationService = authorizationService;
        this.idempotencyStore = idempotencyStore;
        this.fingerprinter = fingerprinter;
        this.auditLogger = auditLogger;
    }

    @Override
    public GatewayResponse<Object> invoke(String rawVersion, String clientId, String idempotencyKey,
                                          ClientCapability capability, Object requestBody, String requestId) {
        String reqId = (requestId == null || requestId.isBlank())
                ? UUID.randomUUID().toString() : requestId;

        // 1. 版本协商
        VersionDecision version = registry.negotiate(rawVersion);
        if (version.status() != VersionStatus.SUPPORTED && version.status() != VersionStatus.DEPRECATED) {
            ErrorType type = switch (version.status()) {
                case MISSING -> ErrorType.VERSION_MISSING;
                case UNKNOWN -> ErrorType.VERSION_UNKNOWN;
                case INCOMPATIBLE -> ErrorType.VERSION_INCOMPATIBLE;
                default -> ErrorType.VERSION_UNKNOWN;
            };
            auditAndThrow(reqId, clientId, capability, rawVersion, version,
                    null, null, "REJECTED:" + type.code(),
                    new GatewayException(type, version.reason(),
                            Map.of("supportedVersions", registry.snapshot())));
        }

        // 2. 权限判定（版本协商通过后进行；拒绝不暴露资源存在性）
        AuthorizationDecision authz = authorizationService.authorize(clientId, capability);
        if (!authz.allowed()) {
            auditAndThrow(reqId, clientId, capability, rawVersion, version,
                    null, authz, "REJECTED:UNAUTHORIZED",
                    new GatewayException(ErrorType.RESOURCE_NOT_FOUND, "resource not found",
                            Map.of()));
        }

        // 3. 幂等键检查
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            auditAndThrow(reqId, clientId, capability, rawVersion, version,
                    IdempotencyStatus.KEY_CONFLICT, authz, "REJECTED:IDEMPOTENCY_KEY_MISSING",
                    new GatewayException(ErrorType.IDEMPOTENCY_KEY_MISSING,
                            "Idempotency-Key header is required"));
        }

        String fingerprint = fingerprinter.fingerprint(requestBody);
        VersionedHandler handler = registry.handlerFor(version.resolved());

        IdempotencyRecordOutcome outcome;
        try {
            outcome = idempotencyStore.executeOnce(
                    idempotencyKey.trim(), fingerprint,
                    () -> handler.handle(requestBody, reqId));
        } catch (GatewayException e) {
            // 业务/校验异常：记录已置为 FAILED，允许同键重试
            audit(reqId, clientId, capability, rawVersion, version,
                    IdempotencyStatus.RETRY_AFTER_FAILURE.name(), authz,
                    "BUSINESS_FAILURE:" + e.errorType().code());
            throw e.withRequestId(reqId);
        } catch (RuntimeException e) {
            audit(reqId, clientId, capability, rawVersion, version,
                    IdempotencyStatus.RETRY_AFTER_FAILURE.name(), authz, "UNEXPECTED_FAILURE");
            throw new GatewayException(ErrorType.INTERNAL_ERROR, "internal error", Map.of(), e)
                    .withRequestId(reqId);
        }

        IdempotencyStatus status = outcome.status();
        return switch (status) {
            case NEW, RETRY_AFTER_FAILURE -> {
                audit(reqId, clientId, capability, rawVersion, version, status.name(), authz,
                        "OK:" + status);
                yield new GatewayResponse<>(reqId, version.resolved().toString(),
                        version.deprecated(),
                        new GatewayResponse.IdempotencyInfo(idempotencyKey.trim(), status.name(), false),
                        outcome.record().getResult());
            }
            case REPLAY_SUCCEEDED -> {
                audit(reqId, clientId, capability, rawVersion, version, status.name(), authz,
                        "OK:REPLAY");
                yield new GatewayResponse<>(reqId, version.resolved().toString(),
                        version.deprecated(),
                        new GatewayResponse.IdempotencyInfo(idempotencyKey.trim(), status.name(), true),
                        outcome.record().getResult());
            }
            case IN_PROGRESS -> auditAndThrow(reqId, clientId, capability, rawVersion, version,
                    status, authz, "REJECTED:IN_PROGRESS",
                    new GatewayException(ErrorType.IDEMPOTENCY_IN_PROGRESS,
                            "a request with the same idempotency key is still being processed",
                            Map.of("idempotencyKey", idempotencyKey.trim())));
            case KEY_CONFLICT -> auditAndThrow(reqId, clientId, capability, rawVersion, version,
                    status, authz, "REJECTED:KEY_CONFLICT",
                    new GatewayException(ErrorType.IDEMPOTENCY_KEY_CONFLICT,
                            "idempotency key was already used with a different request payload",
                            Map.of("idempotencyKey", idempotencyKey.trim())));
        };
    }

    private void audit(String requestId, String clientId, ClientCapability capability,
                       String rawVersion, VersionDecision version,
                       String idempotencyStatus, AuthorizationDecision authz, String result) {
        auditLogger.log(new AuditEvent(
                Instant.now(),
                requestId,
                clientId == null ? "<anonymous>" : clientId,
                capability == null ? null : capability.name(),
                rawVersion,
                version == null ? null : version.status()
                        + "(requested=" + version.requested()
                        + ",resolved=" + version.resolved()
                        + ",deprecated=" + version.deprecated()
                        + ",reason=" + version.reason() + ")",
                idempotencyStatus,
                authz == null ? "not-evaluated" : "allowed=" + authz.allowed()
                        + ",reason=" + authz.reason(),
                result));
    }

    private <T> T auditAndThrow(String requestId, String clientId, ClientCapability capability,
                                String rawVersion, VersionDecision version,
                                IdempotencyStatus idemStatus, AuthorizationDecision authz,
                                String result, GatewayException ex) {
        audit(requestId, clientId, capability, rawVersion, version,
                idemStatus == null ? null : idemStatus.name(), authz, result);
        throw ex.withRequestId(requestId);
    }
}
