package com.github.h3yow.versionedapiidempotencygatewayjava.order.view;

import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiException;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ApiVersion;
import com.github.h3yow.versionedapiidempotencygatewayjava.common.ErrorCode;
import com.github.h3yow.versionedapiidempotencygatewayjava.order.dto.CreateOrderResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按选中版本路由到对应主版本的返回形态处理器。
 *
 * <p>版本协商确定的处理版本（含同主版本兼容落点）在这里真正决定返回结构：
 * 客户端声明的版本落到哪个主版本，就走哪个主版本的塑造方式。
 */
@Component
public class OrderViewRouter {

    private final List<OrderViewShaper> shapers;

    public OrderViewRouter(List<OrderViewShaper> shapers) {
        this.shapers = shapers;
    }

    public Object shape(ApiVersion selected, CreateOrderResponse order) {
        return shapers.stream()
                .filter(s -> s.supports(selected))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL,
                        "没有处理主版本 " + selected.major() + " 的返回形态处理器"))
                .shape(order, selected);
    }
}
