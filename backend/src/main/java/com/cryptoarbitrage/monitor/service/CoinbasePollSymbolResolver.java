package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.config.ExchangeProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Selects which Coinbase products to poll each cycle under the configured per-cycle budget.
 * Core symbols (in config order) are polled first; remaining enabled symbols follow client enable order.
 */
@Service
public class CoinbasePollSymbolResolver {

    private final ExchangeProperties exchangeProperties;

    public CoinbasePollSymbolResolver(ExchangeProperties exchangeProperties) {
        this.exchangeProperties = exchangeProperties;
    }

    /**
     * Resolves Coinbase poll symbols from client-ordered enabled symbols.
     */
    public List<String> resolve(List<String> enabledInOrder) {
        ExchangeProperties.ExchangeConfig config = exchangeProperties.getAdapters().get("coinbase");
        if (config == null) {
            return List.of();
        }
        return resolve(
                enabledInOrder,
                symbol -> config.getMarket(symbol) != null,
                config.getCoreSymbols(),
                config.getMaxProductsPerCycle()
        );
    }

    /**
     * Core-first, then extras in enable order; capped at {@code maxProductsPerCycle}.
     */
    static List<String> resolve(
            List<String> enabledInOrder,
            Predicate<String> supports,
            List<String> coreSymbols,
            int maxProductsPerCycle
    ) {
        if (enabledInOrder == null || enabledInOrder.isEmpty() || maxProductsPerCycle <= 0) {
            return List.of();
        }

        Set<String> enabledLookup = new HashSet<>(enabledInOrder);
        Set<String> coreSet = new HashSet<>(coreSymbols == null ? List.of() : coreSymbols);
        List<String> ordered = new ArrayList<>();

        if (coreSymbols != null) {
            for (String core : coreSymbols) {
                if (enabledLookup.contains(core) && supports.test(core)) {
                    ordered.add(core);
                }
            }
        }

        for (String symbol : enabledInOrder) {
            if (!coreSet.contains(symbol) && supports.test(symbol)) {
                ordered.add(symbol);
            }
        }

        if (ordered.size() <= maxProductsPerCycle) {
            return List.copyOf(ordered);
        }
        return List.copyOf(ordered.subList(0, maxProductsPerCycle));
    }
}
