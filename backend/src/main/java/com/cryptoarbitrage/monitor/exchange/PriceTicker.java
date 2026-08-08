package com.cryptoarbitrage.monitor.exchange;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents normalized bid/ask prices from a single exchange for a single symbol.
 */
public record PriceTicker(
        Exchange exchange,
        String symbol,
        BigDecimal bid,
        BigDecimal ask,
        Instant receivedAt
) {
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
    }
}
