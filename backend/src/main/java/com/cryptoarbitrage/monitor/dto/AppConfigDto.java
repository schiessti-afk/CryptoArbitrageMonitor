package com.cryptoarbitrage.monitor.dto;

import java.time.Instant;
import java.util.List;

public record AppConfigDto(
        int defaultNotional,
        int freshnessWindowMs,
        double neutralEpsilonPercent,
        List<FeeDto> fees
) {
}
