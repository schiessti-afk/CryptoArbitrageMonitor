package com.cryptoarbitrage.monitor.dto;

import com.cryptoarbitrage.monitor.model.TrackedPair;

import java.time.Instant;

public record PairDto(
        Long id,
        String symbol,
        String baseCurrency,
        String quoteCurrency,
        Boolean active,
        Instant createdAt
) {
    public static PairDto from(TrackedPair pair) {
        return new PairDto(
                pair.getId(),
                pair.getSymbol(),
                pair.getBaseCurrency(),
                pair.getQuoteCurrency(),
                pair.getActive(),
                pair.getCreatedAt()
        );
    }
}
