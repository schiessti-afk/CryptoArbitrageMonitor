package com.cryptoarbitrage.monitor.dto;

public record DatabaseStatsDto(
        long sizeBytes,
        String sizePretty,
        long spreadLogRows,
        long spreadLogBytes,
        String spreadLogSizePretty
) {
}
