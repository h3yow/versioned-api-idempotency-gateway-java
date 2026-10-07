package com.github.h3yow.versionedapiidempotencygatewayjava.order.view;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiVersion;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按协商选中的主版本，把统一的订单领域结果 {@link CreateOrderResult}
 * 渲染成对应版本的返回结构，使不同主版本“真正走出不同行为”：
 *
 * <ul>
 *     <li>主版本 1.x：{@link OrderViewV1} 精简结构，仅 orderId/product/quantity/status；</li>
 *     <li>主版本 2.x：{@link OrderViewV2} 扩展结构，附加 amount/createdAt/serverTraceId/apiVersion；</li>
 *     <li>主版本 3.x：{@link OrderViewV3} 在 2.x 扩展之上再附 features 能力提示。</li>
 * </ul>
 *
 * <p>渲染是纯函数（只依赖订单内容与选中版本），因此：同一订单在两个调用入口、
 * 以及重放请求上渲染结果完全一致；同主版本未登记次版本落到兼容处理版本，
 * 自然得到该主版本的形态。
 */
@Component
public class OrderResponsePresenter {

    private static final List<String> V3_FEATURES =
            List.of("extended-order", "replay-safe", "internal-unified-invoke");

    /** 按选中版本字符串（如 "1.2" / "2.0" / "3.0"）渲染返回结构。 */
    public Object render(CreateOrderResult order, String selectedVersion) {
        ApiVersion version = ApiVersion.parse(selectedVersion);
        int major = version.major();
        if (major <= 1) {
            return new OrderViewV1(order.orderId(), order.product(),
                    order.quantity(), order.status());
        }
        if (major == 2) {
            return new OrderViewV2(order.orderId(), order.product(), order.quantity(),
                    order.status(), order.amount(), order.createdAt(),
                    order.orderId(), selectedVersion);
        }
        // major >= 3：当前最新扩展形态
        return new OrderViewV3(order.orderId(), order.product(), order.quantity(),
                order.status(), order.amount(), order.createdAt(),
                order.orderId(), selectedVersion, V3_FEATURES);
    }
}
