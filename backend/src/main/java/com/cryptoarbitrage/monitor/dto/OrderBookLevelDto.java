package com.cryptoarbitrage.monitor.dto;

import com.cryptoarbitrage.monitor.exchange.OrderBookLevel;

import java.math.BigDecimal;

public record OrderBookLevelDto(BigDecimal price, BigDecimal size) {
    public static OrderBookLevelDto from(OrderBookLevel level) {
        return new OrderBookLevelDto(level.price(), level.size());
    }
}
