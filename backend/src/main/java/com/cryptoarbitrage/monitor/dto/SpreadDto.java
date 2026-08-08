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
        Instant calculatedAt
) {
    public static SpreadDto from(SpreadLog log) {
        return new SpreadDto(
                log.getSymbol(),
                log.getBuyExchange(),
                null,  // Native symbols not stored in SpreadLog
                null,
                log.getSellExchange(),
                null,
                null,
                log.getBuyPrice(),
                log.getSellPrice(),
                log.getRawSpreadPercent(),
                log.getNetSpreadPercent(),
                log.getCalculatedAt()
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
                Instant.now()
        );
    }
}
