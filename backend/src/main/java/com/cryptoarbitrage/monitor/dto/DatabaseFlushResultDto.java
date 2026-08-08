package com.cryptoarbitrage.monitor.dto;

public record DatabaseFlushResultDto(
        long deletedRows,
        DatabaseStatsDto stats
) {
}
