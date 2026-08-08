package com.cryptoarbitrage.monitor.dto;

import java.time.Instant;
import java.util.List;

public record SpreadSnapshotDto(
        Instant calculatedAt,
        List<SpreadDto> matrix,
        List<SpreadDto> bestPerSymbol,
        List<ExchangeStatusDto> exchanges,
        int freshExchangeCount,
        boolean live
) {
}
