package com.cryptoarbitrage.monitor.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record FeeDto(
        String exchange,
        BigDecimal takerFee,
        Instant updatedAt
) {
}
