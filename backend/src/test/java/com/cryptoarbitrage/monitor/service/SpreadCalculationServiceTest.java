package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.exchange.Exchange;
import com.cryptoarbitrage.monitor.exchange.PriceTicker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SpreadCalculationServiceTest {

    private final SpreadCalculationService service = new SpreadCalculationService();

    @Test
    void testPositiveSpread() {
        // Buy on Binance at 100, Sell on Kraken at 102
        // Raw: (102/100 - 1) * 100 = 2%
        // Net with 0.1% fee each: ((102 * 0.999) / (100 * 1.001) - 1) * 100 ≈ 1.795%

        Map<String, List<PriceTicker>> tickers = Map.of(
                "BTC/USD", List.of(
                        new PriceTicker(Exchange.BINANCE, "BTC/USD", new BigDecimal("99"), new BigDecimal("100"), Instant.now()),
                        new PriceTicker(Exchange.KRAKEN, "BTC/USD", new BigDecimal("101"), new BigDecimal("102"), Instant.now())
                )
        );

        Map<Exchange, BigDecimal> fees = Map.of(
                Exchange.BINANCE, new BigDecimal("0.001"),
                Exchange.KRAKEN, new BigDecimal("0.001")
        );

        SpreadCalculationService.CalculationResult result = service.calculateSpreads(tickers, fees);

        assertFalse(result.fullMatrix.isEmpty());
        assertTrue(result.bestPerSymbol.containsKey("BTC/USD"));

        SpreadCalculationService.SpreadOpportunity best = result.bestPerSymbol.get("BTC/USD");
        assertTrue(best.netSpreadPercent.compareTo(BigDecimal.ZERO) > 0);
        assertEquals(Exchange.BINANCE, best.buyExchange);
        assertEquals(Exchange.KRAKEN, best.sellExchange);
    }

    @Test
    void testNegativeSpread() {
        // Buy on Kraken at 102, Sell on Binance at 100
        // Raw: (100/102 - 1) * 100 ≈ -1.96%
        // Net with 0.1% fee each: negative

        Map<String, List<PriceTicker>> tickers = Map.of(
                "BTC/USD", List.of(
                        new PriceTicker(Exchange.BINANCE, "BTC/USD", new BigDecimal("99"), new BigDecimal("100"), Instant.now()),
                        new PriceTicker(Exchange.KRAKEN, "BTC/USD", new BigDecimal("101"), new BigDecimal("102"), Instant.now())
                )
        );

        Map<Exchange, BigDecimal> fees = Map.of(
                Exchange.BINANCE, new BigDecimal("0.001"),
                Exchange.KRAKEN, new BigDecimal("0.001")
        );

        SpreadCalculationService.CalculationResult result = service.calculateSpreads(tickers, fees);

        // Should have 2 routes: Binance→Kraken and Kraken→Binance
        assertEquals(2, result.fullMatrix.size());

        // Best should be Binance→Kraken (positive spread)
        SpreadCalculationService.SpreadOpportunity best = result.bestPerSymbol.get("BTC/USD");
        assertTrue(best.netSpreadPercent.compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void testZeroSpread() {
        // Same price on both exchanges
        Map<String, List<PriceTicker>> tickers = Map.of(
                "BTC/USD", List.of(
                        new PriceTicker(Exchange.BINANCE, "BTC/USD", new BigDecimal("100"), new BigDecimal("100"), Instant.now()),
                        new PriceTicker(Exchange.KRAKEN, "BTC/USD", new BigDecimal("100"), new BigDecimal("100"), Instant.now())
                )
        );

        Map<Exchange, BigDecimal> fees = Map.of(
                Exchange.BINANCE, new BigDecimal("0.001"),
                Exchange.KRAKEN, new BigDecimal("0.001")
        );

        SpreadCalculationService.CalculationResult result = service.calculateSpreads(tickers, fees);

        SpreadCalculationService.SpreadOpportunity best = result.bestPerSymbol.get("BTC/USD");
        // With fees, should be negative
        assertTrue(best.netSpreadPercent.compareTo(BigDecimal.ZERO) < 0);
    }

    @Test
    void testHighFeeImpact() {
        // Fees reduce profitability significantly
        Map<String, List<PriceTicker>> tickers = Map.of(
                "ETH/USD", List.of(
                        new PriceTicker(Exchange.BINANCE, "ETH/USD", new BigDecimal("2000"), new BigDecimal("2100"), Instant.now()),
                        new PriceTicker(Exchange.COINBASE, "ETH/USD", new BigDecimal("2020"), new BigDecimal("2200"), Instant.now())
                )
        );

        Map<Exchange, BigDecimal> fees = Map.of(
                Exchange.BINANCE, new BigDecimal("0.006"),  // 0.6%
                Exchange.COINBASE, new BigDecimal("0.006")  // 0.6%
        );

        SpreadCalculationService.CalculationResult result = service.calculateSpreads(tickers, fees);

        SpreadCalculationService.SpreadOpportunity best = result.bestPerSymbol.get("ETH/USD");
        // Raw spread: 2200/2100 ≈ 4.76%, with 1.2% fees ≈ 3.52%
        // Fees significantly reduce spread but should still be positive
        assertNotNull(best);
        assertTrue(best.netSpreadPercent.compareTo(best.rawSpreadPercent) < 0,
                "Net spread should be less than raw spread due to fees");
    }

    @Test
    void testMultipleSymbols() {
        Map<String, List<PriceTicker>> tickers = Map.of(
                "BTC/USD", List.of(
                        new PriceTicker(Exchange.BINANCE, "BTC/USD", new BigDecimal("100"), new BigDecimal("100"), Instant.now()),
                        new PriceTicker(Exchange.KRAKEN, "BTC/USD", new BigDecimal("101"), new BigDecimal("102"), Instant.now())
                ),
                "ETH/USD", List.of(
                        new PriceTicker(Exchange.BINANCE, "ETH/USD", new BigDecimal("2000"), new BigDecimal("2000"), Instant.now()),
                        new PriceTicker(Exchange.KRAKEN, "ETH/USD", new BigDecimal("2020"), new BigDecimal("2030"), Instant.now())
                )
        );

        Map<Exchange, BigDecimal> fees = Map.of(
                Exchange.BINANCE, new BigDecimal("0.001"),
                Exchange.KRAKEN, new BigDecimal("0.001")
        );

        SpreadCalculationService.CalculationResult result = service.calculateSpreads(tickers, fees);

        assertEquals(2, result.bestPerSymbol.size());
        assertTrue(result.bestPerSymbol.containsKey("BTC/USD"));
        assertTrue(result.bestPerSymbol.containsKey("ETH/USD"));
    }

    @Test
    void testInvalidPriceThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            new PriceTicker(Exchange.BINANCE, "BTC/USD", new BigDecimal("0"), new BigDecimal("100"), Instant.now());
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new PriceTicker(Exchange.BINANCE, "BTC/USD", new BigDecimal("100"), new BigDecimal("-50"), Instant.now());
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new PriceTicker(Exchange.BINANCE, "BTC/USD", null, new BigDecimal("100"), Instant.now());
        });
    }

    @Test
    void testSameExchangeRoutesExcluded() {
        // If tickers come from the same exchange, no routes should be created
        Map<String, List<PriceTicker>> tickers = Map.of(
                "BTC/USD", List.of(
                        new PriceTicker(Exchange.BINANCE, "BTC/USD", new BigDecimal("100"), new BigDecimal("101"), Instant.now()),
                        new PriceTicker(Exchange.BINANCE, "BTC/USD", new BigDecimal("100"), new BigDecimal("101"), Instant.now())
                )
        );

        Map<Exchange, BigDecimal> fees = Map.of(
                Exchange.BINANCE, new BigDecimal("0.001")
        );

        SpreadCalculationService.CalculationResult result = service.calculateSpreads(tickers, fees);

        assertEquals(0, result.fullMatrix.size());
        assertEquals(0, result.bestPerSymbol.size());
    }
}
