package com.cryptoarbitrage.monitor.dto;

import com.cryptoarbitrage.monitor.model.SpreadLog;
import com.cryptoarbitrage.monitor.service.SpreadCalculationService;

import java.math.BigDecimal;
import java.time.Instant;

public record SpreadDto(
        String symbol,
        String buyExchange,
        String buyNativeSymbol,
        String buyQuoteAsset,
        String sellExchange,
        String sellNativeSymbol,
        String sellQuoteAsset,
        BigDecimal buyPrice,
        BigDecimal sellPrice,
        BigDecimal rawSpreadPercent,
        BigDecimal netSpreadPercent,
        Instant calculatedAt,
        BigDecimal buyAskSize,
        BigDecimal sellBidSize,
        BigDecimal buyQuoteVolume24h,
        BigDecimal sellQuoteVolume24h
) {
    public static SpreadDto from(SpreadLog log) {
        return new SpreadDto(
                log.getSymbol(),
                log.getBuyExchange(),
                null,
                null,
                log.getSellExchange(),
                null,
                null,
                log.getBuyPrice(),
                log.getSellPrice(),
                log.getRawSpreadPercent(),
                log.getNetSpreadPercent(),
                log.getCalculatedAt(),
                null,
                null,
                null,
                null
        );
    }

    public static SpreadDto from(SpreadCalculationService.SpreadOpportunity opp) {
        return new SpreadDto(
                opp.symbol,
                opp.buyExchange.name(),
                opp.buyNativeSymbol,
                opp.buyQuoteAsset,
                opp.sellExchange.name(),
                opp.sellNativeSymbol,
                opp.sellQuoteAsset,
                opp.buyPrice,
                opp.sellPrice,
                opp.rawSpreadPercent,
                opp.netSpreadPercent,
                Instant.now(),
                opp.buyAskSize,
                opp.sellBidSize,
                opp.buyQuoteVolume24h,
                opp.sellQuoteVolume24h
        );
    }
}
