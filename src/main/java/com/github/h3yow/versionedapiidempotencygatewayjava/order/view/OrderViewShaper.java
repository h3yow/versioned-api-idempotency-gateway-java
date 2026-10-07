package com.github.h3yow.versionedapiidempotencygatewayjava.order.view;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiVersion;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderResponse;

/**
 * 按主版本塑造订单返回形态的处理器：不同主版本走出不同行为。
 */
public interface OrderViewShaper {

    /** 是否负责处理该选中版本（按主版本匹配）。 */
    boolean supports(ApiVersion selected);

    /** 将内部订单模型塑造为该主版本对外的返回结构。 */
    Object shape(CreateOrderResponse order, ApiVersion selected);
}
