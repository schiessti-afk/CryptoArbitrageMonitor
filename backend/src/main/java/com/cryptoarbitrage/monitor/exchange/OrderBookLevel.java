package com.cryptoarbitrage.monitor.exchange;

import java.math.BigDecimal;

public record OrderBookLevel(BigDecimal price, BigDecimal size) {
    public OrderBookLevel {
        if (price == null || size == null) {
            throw new IllegalArgumentException("price and size must not be null");
        }
        if (price.signum() <= 0 || size.signum() <= 0) {
            throw new IllegalArgumentException("price and size must be positive");
        }
    }
}
