package com.github.h3yow.versionedapiidempotencygatewayjava.unified;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiException;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ErrorCode;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.RequestContext;
import com.github.h3yow.versionedapiidempotencygatewayjava.gateway.GatewayResult;
import com.github.h3yow.versionedapiidempotencygatewayjava.gateway.GatewayService;
import com.github.h3yow.versionedapiidempotencygatewayjava.auth.AuthDecision;
import com.github.h3yow.versionedapiidempotencygatewayjava.auth.AuthorizationService;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * 统一调用入口编排：把 {能力, 操作, 参数} 映射到既有业务编排，
 * 复用同一套鉴权、版本协商、幂等与异常返回。
 *
 * <p>映射关系（当前仅 orders 能力）：
 * <ul>
 *     <li>{@code orders/createOrder}（写，强制幂等键）-> {@link GatewayService#createOrder}；</li>
 *     <li>{@code orders/getOrder}（读）-> {@link GatewayService#getOrder}，
 *     未授权/不存在同构返回空。</li>
 * </ul>
 *
 * <p>参数被重建为与业务端点完全一致的 {@link CreateOrderRequest}，因此请求负载指纹一致，
 * 同一输入在两个入口的幂等结论（EXECUTED/REPLAYED/IN_PROGRESS/KEY_CONFLICT/...）完全对得上。
 */
@Service
public class UnifiedGatewayService {

    public static final String OP_CREATE_ORDER = "createOrder";
    public static final String OP_GET_ORDER = "getOrder";

    private final GatewayService gatewayService;
    private final AuthorizationService authorizationService;

    public UnifiedGatewayService(GatewayService gatewayService,
                                 AuthorizationService authorizationService) {
        this.gatewayService = gatewayService;
        this.authorizationService = authorizationService;
    }

    /**
     * 认证前置：在识别能力/操作之前执行。这样未认证调用即使面对未知能力/操作/资源，
     * 也统一得到 401，与业务端点“认证先于版本/幂等判定、不暴露存在性”完全对齐。
     */
    public void requireAuthenticated(RequestContext context) {
        AuthDecision authentication = authorizationService.authenticate(context.clientId());
        if (!authentication.authenticated()) {
            throw new ApiException(ErrorCode.AUTH_UNAUTHENTICATED, authentication.reason());
        }
    }

    /** 是否为写操作（写操作要求幂等键并走幂等执行）。 */
    public boolean isWriteOperation(UnifiedInvokeRequest request) {
        return request != null
                && GatewayService.CAPABILITY_ORDER.equals(request.capability())
                && OP_CREATE_ORDER.equals(request.operation());
    }

    /** 是否为可识别的读操作。 */
    public boolean isReadOperation(UnifiedInvokeRequest request) {
        return request != null
                && GatewayService.CAPABILITY_ORDER.equals(request.capability())
                && OP_GET_ORDER.equals(request.operation());
    }

    /** 写操作（强制幂等键）。 */
    public GatewayResult<?> invoke(RequestContext context, UnifiedInvokeRequest request) {
        validateKnown(context, request, true);
        Map<String, Object> params = paramsOf(request);
        CreateOrderRequest orderRequest = new CreateOrderRequest(
                asString(params.get("product")),
                asInt(params.get("quantity")),
                asDecimal(params.get("amount")));
        return gatewayService.createOrder(context, orderRequest);
    }

    /** 读操作（不使用幂等键；未授权/不存在同构返回空）。 */
    public Optional<GatewayResult<?>> invokeRead(RequestContext context, UnifiedInvokeRequest request) {
        validateKnown(context, request, false);
        Map<String, Object> params = paramsOf(request);
        String orderId = asString(params.get("orderId"));
        if (orderId == null || orderId.isBlank()) {
            throw new ApiException(ErrorCode.INVOKE_REQUEST_INVALID,
                    "操作 getOrder 缺少必填参数 orderId");
        }
        @SuppressWarnings("unchecked")
        Optional<GatewayResult<?>> result = (Optional<GatewayResult<?>>) (Optional<?>)
                gatewayService.getOrder(context, orderId.trim());
        return result;
    }

    /**
     * 校验能力/操作可识别（错误稳定、可区分）。鉴权在更外层的业务编排中先执行，
     * 因此未认证时即使能力/操作未知也仍先返回 401，不暴露资源是否存在。
     */
    private void validateKnown(RequestContext context, UnifiedInvokeRequest request, boolean write) {
        if (request == null) {
            throw new ApiException(ErrorCode.INVOKE_REQUEST_INVALID,
                    "统一调用入口需要 JSON 请求体 {capability, operation, params}");
        }
        if (request.capability() == null || request.capability().isBlank()) {
            throw new ApiException(ErrorCode.INVOKE_REQUEST_INVALID, "缺少必填字段 capability");
        }
        if (request.operation() == null || request.operation().isBlank()) {
            throw new ApiException(ErrorCode.INVOKE_REQUEST_INVALID, "缺少必填字段 operation");
        }
        if (!GatewayService.CAPABILITY_ORDER.equals(request.capability())) {
            throw new ApiException(ErrorCode.CAPABILITY_UNKNOWN,
                    "未知能力 '" + request.capability() + "'，当前统一入口支持的能力：orders");
        }
        boolean known = write ? isWriteOperation(request) : isReadOperation(request);
        if (!known) {
            throw new ApiException(ErrorCode.OPERATION_UNKNOWN,
                    "能力 '" + request.capability() + "' 下不存在操作 '"
                            + request.operation() + "'，支持：createOrder/getOrder");
        }
    }

    private static Map<String, Object> paramsOf(UnifiedInvokeRequest request) {
        return request.params() == null ? Map.of() : request.params();
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static int asInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            throw new ApiException(ErrorCode.INVOKE_REQUEST_INVALID,
                    "参数 quantity 必须是整数，实际为 '" + value + "'");
        }
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            throw new ApiException(ErrorCode.INVOKE_REQUEST_INVALID,
                    "参数 amount 必须是数值，实际为 '" + value + "'");
        }
    }
}
