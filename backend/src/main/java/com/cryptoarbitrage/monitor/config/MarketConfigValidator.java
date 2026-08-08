package com.cryptoarbitrage.monitor.config;

import com.cryptoarbitrage.monitor.model.TrackedPair;
import com.cryptoarbitrage.monitor.repository.TrackedPairRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Validates that every venue configured for a given internal symbol (e.g. "BTC/USD") agrees on
 * the quote asset for it. Each internal symbol is meant to name one quote-asset universe — the
 * "BTC/USD" vs "BTC/USDT" split lives in the symbol itself, not in per-venue interpretation — so
 * this should never fire in a correctly configured system. It exists to catch a config typo
 * (e.g. a venue accidentally setting {@code quote-asset=USD} under a {@code BTC_USDT} market key)
 * before it silently mixes quote assets into one comparison.
 */
@Component
public class MarketConfigValidator {
    private static final Logger log = LoggerFactory.getLogger(MarketConfigValidator.class);

    private final ExchangeProperties properties;
    private final TrackedPairRepository trackedPairRepository;

    public MarketConfigValidator(ExchangeProperties properties, TrackedPairRepository trackedPairRepository) {
        this.properties = properties;
        this.trackedPairRepository = trackedPairRepository;
        validateConfiguration();
    }

    private void validateConfiguration() {
        Map<String, Set<String>> symbolToQuotes = new HashMap<>();
        Map<String, Integer> symbolToVenueCount = new HashMap<>();

        for (var adapterEntry : properties.getAdapters().entrySet()) {
            ExchangeProperties.ExchangeConfig config = adapterEntry.getValue();
            for (var marketEntry : config.getMarkets().entrySet()) {
                String configKey = marketEntry.getKey();               // e.g. "BTC_USDT"
                String symbol = configKey.replace("_", "/");           // e.g. "BTC/USDT"
                String quoteAsset = marketEntry.getValue().getQuoteAsset();
                symbolToQuotes.computeIfAbsent(symbol, k -> new HashSet<>()).add(quoteAsset);
                symbolToVenueCount.merge(symbol, 1, Integer::sum);
            }
        }

        for (var entry : symbolToQuotes.entrySet()) {
            if (entry.getValue().size() > 1) {
                log.warn("Symbol {} is configured with mixed quote assets across venues: {}. "
                        + "Every venue offering this internal symbol must agree on its quote asset — "
                        + "check for a config typo (e.g. quote-asset=USD under a *_USDT market key).",
                        entry.getKey(), entry.getValue());
            }
        }

        for (var entry : symbolToVenueCount.entrySet()) {
            if (entry.getValue() < 2) {
                log.warn("Symbol {} is offered by only {} venue(s) — cross-venue spreads are impossible. "
                        + "Either add another venue or remove the tracked_pair row.",
                        entry.getKey(), entry.getValue());
            }
        }

        Set<String> configuredSymbols = properties.getConfiguredSymbols();
        Set<String> activeDbSymbols = new HashSet<>();
        for (TrackedPair pair : trackedPairRepository.findByActiveTrue()) {
            activeDbSymbols.add(pair.getSymbol());
            if (!configuredSymbols.contains(pair.getSymbol())) {
                log.warn("Active tracked_pair {} has no venue config — it will never be polled.",
                        pair.getSymbol());
            }
        }

        for (String configured : configuredSymbols) {
            if (!activeDbSymbols.contains(configured)) {
                log.warn("Configured market {} has no active tracked_pair row — it will never be polled.",
                        configured);
            }
        }
    }
}
