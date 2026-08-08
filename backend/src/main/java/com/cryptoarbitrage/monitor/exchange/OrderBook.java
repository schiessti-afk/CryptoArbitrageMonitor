package com.cryptoarbitrage.monitor.exchange;

import java.time.Instant;
import java.util.List;

public record OrderBook(
        Exchange exchange,
        String symbol,
        String nativeSymbol,
        List<OrderBookLevel> bids,
        List<OrderBookLevel> asks,
        Instant receivedAt
) {
    public OrderBook {
        if (bids == null || asks == null) {
            throw new IllegalArgumentException("bids and asks must not be null");
        }
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt must not be null");
        }
    }
}
