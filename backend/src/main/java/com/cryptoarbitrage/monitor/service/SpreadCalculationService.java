package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.exchange.Exchange;
import com.cryptoarbitrage.monitor.exchange.PriceTicker;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pure spread calculation service.
 * Takes normalized tickers and fees, returns full matrix + best routes per symbol.
 * No persistence, no timing, no state — stateless math.
 */
@Service
public class SpreadCalculationService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal ONE = new BigDecimal("1");

    public static class SpreadOpportunity {
        public final String symbol;
        public final Exchange buyExchange;
        public final String buyNativeSymbol;
        public final String buyQuoteAsset;
        public final Exchange sellExchange;
        public final String sellNativeSymbol;
        public final String sellQuoteAsset;
        public final BigDecimal buyPrice;
        public final BigDecimal sellPrice;
        public final BigDecimal rawSpreadPercent;
        public final BigDecimal netSpreadPercent;

        public SpreadOpportunity(
                String symbol,
                Exchange buyExchange,
                String buyNativeSymbol,
                String buyQuoteAsset,
                Exchange sellExchange,
                String sellNativeSymbol,
                String sellQuoteAsset,
                BigDecimal buyPrice,
                BigDecimal sellPrice,
                BigDecimal rawSpreadPercent,
                BigDecimal netSpreadPercent
        ) {
            this.symbol = symbol;
            this.buyExchange = buyExchange;
            this.buyNativeSymbol = buyNativeSymbol;
            this.buyQuoteAsset = buyQuoteAsset;
            this.sellExchange = sellExchange;
            this.sellNativeSymbol = sellNativeSymbol;
            this.sellQuoteAsset = sellQuoteAsset;
            this.buyPrice = buyPrice;
            this.sellPrice = sellPrice;
            this.rawSpreadPercent = rawSpreadPercent;
            this.netSpreadPercent = netSpreadPercent;
        }
    }

    public static class CalculationResult {
        public final List<SpreadOpportunity> fullMatrix;
        public final Map<String, SpreadOpportunity> bestPerSymbol;

        public CalculationResult(List<SpreadOpportunity> fullMatrix, Map<String, SpreadOpportunity> bestPerSymbol) {
            this.fullMatrix = fullMatrix;
            this.bestPerSymbol = bestPerSymbol;
        }
    }

    /**
     * Calculate spreads for all symbols given available tickers and exchange fees.
     *
     * @param tickers Map of symbol -> List of PriceTickers from different exchanges
     * @param fees    Map of exchange name -> taker fee ratio (e.g., 0.001 for 0.1%)
     * @return CalculationResult containing full matrix and best route per symbol
     */
    public CalculationResult calculateSpreads(
            Map<String, List<PriceTicker>> tickers,
            Map<Exchange, BigDecimal> fees
    ) {
        List<SpreadOpportunity> fullMatrix = new ArrayList<>();

        for (Map.Entry<String, List<PriceTicker>> entry : tickers.entrySet()) {
            String symbol = entry.getKey();
            List<PriceTicker> symbolTickers = entry.getValue();

            // Build all buy/sell pairs for this symbol
            for (PriceTicker buyTicker : symbolTickers) {
                for (PriceTicker sellTicker : symbolTickers) {
                    // Skip same-exchange routes
                    if (buyTicker.exchange() == sellTicker.exchange()) {
                        continue;
                    }

                    BigDecimal buyFee = fees.getOrDefault(buyTicker.exchange(), BigDecimal.ZERO);
                    BigDecimal sellFee = fees.getOrDefault(sellTicker.exchange(), BigDecimal.ZERO);

                    SpreadOpportunity opp = calculateSpread(
                            symbol,
                            buyTicker,
                            sellTicker,
                            buyFee,
                            sellFee
                    );

                    fullMatrix.add(opp);
                }
            }
        }

        // Find best (highest net spread) per symbol
        Map<String, SpreadOpportunity> bestPerSymbol = fullMatrix.stream()
                .collect(Collectors.toMap(
                        opp -> opp.symbol,
                        opp -> opp,
                        (existing, candidate) -> {
                            if (candidate.netSpreadPercent.compareTo(existing.netSpreadPercent) > 0) {
                                return candidate;
                            }
                            return existing;
                        }
                ));

        return new CalculationResult(fullMatrix, bestPerSymbol);
    }

    /**
     * Calculate raw and net spread for a single buy/sell route.
     */
    private SpreadOpportunity calculateSpread(
            String symbol,
            PriceTicker buyTicker,
            PriceTicker sellTicker,
            BigDecimal buyFee,
            BigDecimal sellFee
    ) {
        BigDecimal buyPrice = buyTicker.ask();
        BigDecimal sellPrice = sellTicker.bid();

        // Raw spread: ((sell / buy) - 1) * 100
        BigDecimal rawSpreadPercent = sellPrice.divide(buyPrice, 8, RoundingMode.HALF_UP)
                .subtract(ONE)
                .multiply(ONE_HUNDRED)
                .setScale(6, RoundingMode.HALF_UP);

        // Effective cost and revenue
        BigDecimal effectiveBuyCost = buyPrice.multiply(ONE.add(buyFee));
        BigDecimal effectiveSellRevenue = sellPrice.multiply(ONE.subtract(sellFee));

        // Net spread: ((effective_revenue / effective_cost) - 1) * 100
        BigDecimal netSpreadPercent = effectiveSellRevenue.divide(effectiveBuyCost, 8, RoundingMode.HALF_UP)
                .subtract(ONE)
                .multiply(ONE_HUNDRED)
                .setScale(6, RoundingMode.HALF_UP);

        return new SpreadOpportunity(
                symbol,
                buyTicker.exchange(),
                buyTicker.nativeSymbol(),
                buyTicker.quoteAsset(),
                sellTicker.exchange(),
                sellTicker.nativeSymbol(),
                sellTicker.quoteAsset(),
                buyPrice,
                sellPrice,
                rawSpreadPercent,
                netSpreadPercent
        );
    }
}
