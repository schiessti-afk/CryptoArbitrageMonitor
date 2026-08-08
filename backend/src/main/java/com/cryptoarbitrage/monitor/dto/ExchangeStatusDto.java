package com.cryptoarbitrage.monitor.dto;

import com.cryptoarbitrage.monitor.exchange.Exchange;

import java.time.Instant;
import java.util.List;

public record ExchangeStatusDto(
        String exchange,
        Boolean available,
        Instant lastUpdate,
        String freshness,
        List<String> offeredQuoteAssets
) {
    public static ExchangeStatusDto from(Exchange exchange, Instant lastUpdate, boolean isFresh, List<String> offeredQuoteAssets) {
        return new ExchangeStatusDto(
                exchange.name(),
                lastUpdate != null,
                lastUpdate,
                isFresh ? "FRESH" : (lastUpdate != null ? "STALE" : "NEVER"),
                offeredQuoteAssets
        );
    }
}
