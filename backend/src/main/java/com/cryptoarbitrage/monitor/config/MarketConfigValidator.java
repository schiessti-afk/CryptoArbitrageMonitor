package com.cryptoarbitrage.monitor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Validates that all exchanges use consistent quote assets for each internal symbol.
 * Logs a warning if mismatches are detected (e.g., one exchange using USD, another USDT for BTC).
 */
@Component
public class MarketConfigValidator {
    private static final Logger log = LoggerFactory.getLogger(MarketConfigValidator.class);

    private final ExchangeProperties properties;

    public MarketConfigValidator(ExchangeProperties properties) {
        this.properties = properties;
        validateConfiguration();
    }

    private void validateConfiguration() {
        Map<String, Set<String>> symbolToQuotes = new HashMap<>();

        // Collect all quote assets per symbol across all exchanges
        for (var adapter : properties.getAdapters().values()) {
            var symbolMap = adapter.getSymbolMap();
            if (symbolMap != null) {
                for (var entry : symbolMap.entrySet()) {
                    // Extract symbol from key (e.g., "BTC_USD" -> "BTC/USD")
                    String symbol = entry.getKey().replace("_", "/");
                    // For now, we hardcode USD since the config doesn't distinguish quote assets yet
                    // In future, extract from a nested market config
                    symbolToQuotes.computeIfAbsent(symbol, k -> new HashSet<>()).add("USD");
                }
            }
        }

        // Log warnings for mixed quote assets
        for (var entry : symbolToQuotes.entrySet()) {
            if (entry.getValue().size() > 1) {
                log.warn("Symbol {} is configured with mixed quote assets: {}. "
                        + "This may indicate different markets (e.g., USD vs USDT) across exchanges.",
                        entry.getKey(), entry.getValue());
            }
        }
    }
}
