package com.cryptoarbitrage.monitor.dto;

public record SymbolCoverageDto(
        String symbol,
        String quoteAsset,
        int configuredVenues,
        int freshVenues
) {
}
