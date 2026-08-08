package com.cryptoarbitrage.monitor.exchange;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents normalized bid/ask prices from a single exchange for a single symbol.
 * Includes native market details (exchange-specific symbol and quote asset) for transparency.
 * Optional liquidity fields are populated when the venue's ticker payload exposes them.
 */
public record PriceTicker(
        Exchange exchange,
        String symbol,
        String nativeSymbol,
        String quoteAsset,
        BigDecimal bid,
        BigDecimal ask,
        Instant receivedAt,
        BigDecimal bidSize,
        BigDecimal askSize,
        BigDecimal quoteVolume24h
) {
    public PriceTicker(
            Exchange exchange,
            String symbol,
            String nativeSymbol,
            String quoteAsset,
            BigDecimal bid,
            BigDecimal ask,
            Instant receivedAt
    ) {
        this(exchange, symbol, nativeSymbol, quoteAsset, bid, ask, receivedAt, null, null, null);
    }

    public PriceTicker {
        if (bid == null || ask == null) {
            throw new IllegalArgumentException("bid and ask must not be null");
        }
        if (bid.signum() <= 0 || ask.signum() <= 0) {
            throw new IllegalArgumentException("bid and ask must be positive");
        }
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt must not be null");
        }
        if (nativeSymbol == null || nativeSymbol.isEmpty()) {
            throw new IllegalArgumentException("nativeSymbol must not be null or empty");
        }
        if (quoteAsset == null || quoteAsset.isEmpty()) {
            throw new IllegalArgumentException("quoteAsset must not be null or empty");
        }
    }
}
