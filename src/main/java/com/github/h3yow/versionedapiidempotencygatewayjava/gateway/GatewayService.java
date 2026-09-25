package com.github.h3yow.versionedapiidempotencygatewayjava.gateway;

import com.github.h3yow.versionedapiidempotencygatewayjava.audit.AuditEntry;
import com.github.h3yow.versionedapiidempotencygatewayjava.audit.AuditLogger;
import com.github.h3yow.versionedapiidempotencygatewayjava.auth.AuthDecision;
import com.github.h3yow.versionedapiidempotencygatewayjava.auth.AuthorizationService;
import com.github.h3yow.versionedapiidempotencygatewayjava.auth.ClientDirectory;
import com.github.h3yow.versionedapiidempotencygatewayjava.auth.ClientProfile;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiException;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ErrorCode;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.RequestContext;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyOutcome;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyResult;
import com.github.h3yow.versionedapiidempotencygatewayjava.idempotency.IdempotencyService;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.OrderService;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderRequest;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderResponse;
import com.github.h3yow.versionedapiidempotencygatewayjava.version.VersionDecision;
import com.github.h3yow.versionedapiidempotencygatewayjava.version.VersionNegotiator;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 网关编排：鉴权 -> 版本协商 -> 幂等执行 -> 业务能力，并写审计。
 *
 * <p>所有调用入口（HTTP 端点或测试直连）都经由本类，保证版本决策、重复提交、
 * 权限判定与异常映射一致且可解释；每个关键判定都落一条审计记录。
 */
@Service
public class GatewayService {

    public static final String CAPABILITY_ORDER = "orders";
    public static final String SCOPE_ORDER_WRITE = "orders:write";
    public static final String SCOPE_ORDER_READ = "orders:read";

    private final AuthorizationService authorizationService;
    private final ClientDirectory clientDirectory;
    private final VersionNegotiator versionNegotiator;
    private final IdempotencyService idempotencyService;
    private final OrderService orderService;
    private final AuditLogger auditLogger;

    public GatewayService(AuthorizationService authorizationService,
                          ClientDirectory clientDirectory,
                          VersionNegotiator versionNegotiator,
                          IdempotencyService idempotencyService,
                          OrderService orderService,
                          AuditLogger auditLogger) {
        this.authorizationService = authorizationService;
        this.clientDirectory = clientDirectory;
        this.versionNegotiator = versionNegotiator;
        this.idempotencyService = idempotencyService;
        this.orderService = orderService;
        this.auditLogger = auditLogger;
    }

    /**
     * 创建订单（写能力）：强制幂等键；失败时抛出 {@link ApiException} 由入口统一映射。
     */
    public GatewayResult<CreateOrderResponse> createOrder(RequestContext context, CreateOrderRequest request) {
        // 1) 鉴权（认证 + 写能力范围）
        ClientProfile client = requireAuthenticated(context);
        AuthDecision scopeDecision =
                authorizationService.authorize(client, SCOPE_ORDER_WRITE);
        if (!scopeDecision.authorized()) {
            auditAndThrow(context, "N/A", null, scopeDecision,
                    ErrorCode.AUTH_FORBIDDEN, scopeDecision.reason());
        }

        // 2) 版本协商
        VersionDecision versionDecision = versionNegotiator.negotiate(CAPABILITY_ORDER, context);
        if (!versionDecision.accepted()) {
            auditAndThrow(context, "N/A", null, scopeDecision,
                    versionDecision.rejectionCode(), versionDecision.reason());
        }

        // 3) 幂等键（写请求必须携带）
        if (context.idempotencyKey() == null || context.idempotencyKey().isBlank()) {
            auditAndThrow(context, versionDecision.reason(), null, scopeDecision,
                    ErrorCode.IDEMPOTENCY_KEY_MISSING,
                    "写请求必须携带 Idempotency-Key 请求头");
        }
        String fingerprint = request == null ? "body:<null>" : request.fingerprint();

        // 4) 幂等执行（并发下业务动作至多一次）
        IdempotencyResult<CreateOrderResponse> idem = idempotencyService.execute(
                context.idempotencyKey(), fingerprint, context.requestId(),
                () -> orderService.create(request));

        // 5) 依据幂等结论映射
        switch (idem.outcome()) {
            case KEY_CONFLICT -> auditAndThrow(context, versionDecision.reason(),
                    idem.outcome(), scopeDecision,
                    ErrorCode.IDEMPOTENCY_KEY_CONFLICT, idem.errorMessage());
            case IN_PROGRESS -> auditAndThrow(context, versionDecision.reason(),
                    idem.outcome(), scopeDecision,
                    ErrorCode.IDEMPOTENCY_IN_PROGRESS, idem.errorMessage());
            default -> {
                // EXECUTED / RETRY_AFTER_FAILURE 业务失败：抛统一契约，不产生成功响应
                if (idem.errorCode() != null) {
                    ErrorCode code = resolveCode(idem.errorCode());
                    auditAndThrow(context, versionDecision.reason(),
                            idem.outcome(), scopeDecision, code, idem.errorMessage());
                }
                auditLogger.record(new AuditEntry(context.requestId(), context.clientId(),
                        CAPABILITY_ORDER, context.declaredVersion(),
                        versionDecision.reason(), idem.outcome().name(),
                        scopeDecision.reason(), "SUCCESS:" + idem.value().orderId(),
                        System.currentTimeMillis()));
                return new GatewayResult<>(idem.value(),
                        versionDecision.selectedStatus() == com.github.h3yow.versionedapiidempotencygatewayjava.version.VersionStatus.DEPRECATED,
                        versionDecision.sunset(),
                        versionDecision.selected().asString(),
                        idem.outcome().name());
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /**
     * 查询订单（读能力）：未授权一律以“资源不存在”回应，不泄露资源是否存在。
     */
    public Optional<GatewayResult<CreateOrderResponse>> getOrder(RequestContext context, String orderId) {
        ClientProfile client = requireAuthenticated(context);
        AuthDecision scopeDecision =
                authorizationService.authorize(client, SCOPE_ORDER_READ);
        if (!scopeDecision.authorized()) {
            // 越权访问：对外表现与资源不存在完全一致（同样 404），真实原因只进审计
            auditLogger.record(new AuditEntry(context.requestId(), context.clientId(),
                    CAPABILITY_ORDER, context.declaredVersion(),
                    "N/A", "N/A", scopeDecision.reason(),
                    "DENIED_AS_NOT_FOUND:" + orderId, System.currentTimeMillis()));
            return Optional.empty();
        }

        VersionDecision versionDecision = versionNegotiator.negotiate(CAPABILITY_ORDER, context);
        if (!versionDecision.accepted()) {
            auditAndThrow(context, "N/A", null, scopeDecision,
                    versionDecision.rejectionCode(), versionDecision.reason());
        }

        Optional<CreateOrderResponse> found = orderService.findById(orderId);
        auditLogger.record(new AuditEntry(context.requestId(), context.clientId(),
                CAPABILITY_ORDER, context.declaredVersion(),
                versionDecision.reason(), "N/A", scopeDecision.reason(),
                found.map(r -> "SUCCESS:" + r.orderId()).orElse("NOT_FOUND:" + orderId),
                System.currentTimeMillis()));
        return found.map(body -> new GatewayResult<>(body,
                versionDecision.selectedStatus() == com.github.h3yow.versionedapiidempotencygatewayjava.version.VersionStatus.DEPRECATED,
                versionDecision.sunset(),
                versionDecision.selected().asString(),
                null));
    }

    private ClientProfile requireAuthenticated(RequestContext context) {
        AuthDecision authentication = authorizationService.authenticate(context.clientId());
        if (!authentication.authenticated()) {
            auditAndThrow(context, "N/A", null, authentication,
                    ErrorCode.AUTH_UNAUTHENTICATED, authentication.reason());
        }
        return clientDirectory.find(context.clientId());
    }

    private void auditAndThrow(RequestContext context, String versionReason,
                               IdempotencyOutcome idemOutcome, AuthDecision authDecision,
                               ErrorCode code, String message) {
        auditLogger.record(new AuditEntry(context.requestId(), context.clientId(),
                CAPABILITY_ORDER, context.declaredVersion(),
                versionReason,
                idemOutcome == null ? "N/A" : idemOutcome.name(),
                authDecision == null ? "N/A" : authDecision.reason(),
                "REJECTED:" + code.name(), System.currentTimeMillis()));
        throw new ApiException(code, message);
    }

    private static ErrorCode resolveCode(String name) {
        try {
            return ErrorCode.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return ErrorCode.BUSINESS_ERROR;
        }
    }
}
