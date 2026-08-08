package com.cryptoarbitrage.monitor.dto;

import com.cryptoarbitrage.monitor.exchange.OrderBook;

import java.time.Instant;
import java.util.List;

public record OrderBookDto(
        String exchange,
        String symbol,
        String nativeSymbol,
        List<OrderBookLevelDto> bids,
        List<OrderBookLevelDto> asks,
        Instant receivedAt
) {
    public static OrderBookDto from(OrderBook book) {
        return new OrderBookDto(
                book.exchange().name(),
                book.symbol(),
                book.nativeSymbol(),
                book.bids().stream().map(OrderBookLevelDto::from).toList(),
                book.asks().stream().map(OrderBookLevelDto::from).toList(),
                book.receivedAt()
        );
    }
}
