package com.github.h3yow.versionedapiidempotencygatewayjava.order.view;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiVersion;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderResponse;
import org.springframework.stereotype.Component;

/**
 * 旧主版本（1.x）处理器：返回精简结构，只保留 orderId 与 status 两个核心字段。
 */
@Component
public class CompactOrderViewShaper implements OrderViewShaper {

    static final int SUPPORTED_MAJOR = 1;

    @Override
    public boolean supports(ApiVersion selected) {
        return selected != null && selected.major() == SUPPORTED_MAJOR;
    }

    @Override
    public Object shape(CreateOrderResponse order, ApiVersion selected) {
        return new CompactOrderView(order.orderId(), order.status());
    }
}
