package com.github.h3yow.versionedapiidempotencygatewayjava.order.view;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiVersion;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 当前主版本（2.x/3.x）处理器：返回带扩展信息的结构。
 *
 * <p>扩展信息包括：视图档案标识（按主版本区分，2.x 与 3.x 可辨别）、
 * 实际生效的 API 版本、该主版本暴露的能力清单与资源链接；
 * 3.x 在 2.x 基础上追加部分退款能力，使不同主版本的返回可被客户端直接区分。
 */
@Component
public class ExtendedOrderViewShaper implements OrderViewShaper {

    private static final List<String> CAPABILITIES_V2 =
            List.of("order-tracking", "cancel-support");
    private static final List<String> CAPABILITIES_V3 =
            List.of("order-tracking", "cancel-support", "partial-refund");

    @Override
    public boolean supports(ApiVersion selected) {
        return selected != null && selected.major() >= 2;
    }

    @Override
    public Object shape(CreateOrderResponse order, ApiVersion selected) {
        List<String> capabilities = selected.major() >= 3 ? CAPABILITIES_V3 : CAPABILITIES_V2;
        return new ExtendedOrderView(
                order.orderId(),
                order.product(),
                order.quantity(),
                order.status(),
                "extended-v" + selected.major(),
                selected.asString(),
                capabilities,
                Map.of("self", "/api/orders/" + order.orderId(),
                        "tracking", "/api/orders/" + order.orderId() + "/tracking"));
    }
}
