package com.github.h3yow.versionedapiidempotencygatewayjava.web.handler;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单业务副作用计数器。每次真实创建订单（产生副作用）恰好递增一次。
 * 幂等回放/进行中重试不得递增。测试用其断言"副作用仅发生一次"。
 */
@Component
public class OrderSideEffectCounter {

    private final AtomicLong count = new AtomicLong();

    public long increment() {
        return count.incrementAndGet();
    }

    public long get() {
        return count.get();
    }

    public void reset() {
        count.set(0);
    }
}
