package com.cryptoarbitrage.monitor.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AppConfigDto(
        BigDecimal defaultNotional,
        long freshnessWindowMs,
        double neutralEpsilonPercent,
        List<FeeDto> fees,
        List<String> quoteAssets
) {
}
