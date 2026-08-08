package com.cryptoarbitrage.monitor.dto;

import com.cryptoarbitrage.monitor.exchange.Exchange;

import java.time.Instant;

public record ExchangeStatusDto(
        String exchange,
        Boolean available,
        Instant lastUpdate,
        String freshness
) {
    public static ExchangeStatusDto from(Exchange exchange, Instant lastUpdate, boolean isFresh) {
        return new ExchangeStatusDto(
                exchange.name(),
                lastUpdate != null,
                lastUpdate,
                isFresh ? "FRESH" : (lastUpdate != null ? "STALE" : "NEVER")
        );
    }
}
