package com.cryptoarbitrage.monitor.dto;

import com.cryptoarbitrage.monitor.model.SpreadLog;
import com.cryptoarbitrage.monitor.service.SpreadCalculationService;

import java.math.BigDecimal;
import java.time.Instant;

public record SpreadDto(
        String symbol,
        String buyExchange,
        String sellExchange,
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
                log.getSellExchange(),
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
                opp.sellExchange.name(),
                opp.buyPrice,
                opp.sellPrice,
                opp.rawSpreadPercent,
                opp.netSpreadPercent,
                Instant.now()
        );
    }
}
