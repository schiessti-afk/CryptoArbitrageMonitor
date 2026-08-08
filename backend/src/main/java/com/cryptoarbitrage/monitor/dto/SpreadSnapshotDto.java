package com.cryptoarbitrage.monitor.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SpreadSnapshotDto(
        Instant calculatedAt,
        List<SpreadDto> matrix,
        List<SpreadDto> bestPerSymbol,
        List<ExchangeStatusDto> exchanges,
        int freshExchangeCount,
        boolean live,
        Map<String, Boolean> liveByQuote,
        Map<String, Integer> freshCountByQuote,
        List<SymbolCoverageDto> coverage
) {
}
