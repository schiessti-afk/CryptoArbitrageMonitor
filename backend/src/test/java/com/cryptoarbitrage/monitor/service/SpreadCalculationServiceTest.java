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
                        new PriceTicker(Exchange.BINANCE, "BTC/USD", "BTCUSD", "USD", new BigDecimal("99"), new BigDecimal("100"), Instant.now()),
                        new PriceTicker(Exchange.KRAKEN, "BTC/USD", "XXBTZUSD", "USD", new BigDecimal("101"), new BigDecimal("102"), Instant.now())
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
                        new PriceTicker(Exchange.BINANCE, "BTC/USD", "BTCUSD", "USD", new BigDecimal("99"), new BigDecimal("100"), Instant.now()),
                        new PriceTicker(Exchange.KRAKEN, "BTC/USD", "XXBTZUSD", "USD", new BigDecimal("101"), new BigDecimal("102"), Instant.now())
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
                        new PriceTicker(Exchange.BINANCE, "BTC/USD", "BTCUSD", "USD", new BigDecimal("100"), new BigDecimal("100"), Instant.now()),
                        new PriceTicker(Exchange.KRAKEN, "BTC/USD", "XXBTZUSD", "USD", new BigDecimal("100"), new BigDecimal("100"), Instant.now())
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
                        new PriceTicker(Exchange.BINANCE, "ETH/USD", "ETHUSD", "USD", new BigDecimal("2000"), new BigDecimal("2100"), Instant.now()),
                        new PriceTicker(Exchange.COINBASE, "ETH/USD", "ETH-USD", "USD", new BigDecimal("2020"), new BigDecimal("2200"), Instant.now())
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
                        new PriceTicker(Exchange.BINANCE, "BTC/USD", "BTCUSD", "USD", new BigDecimal("100"), new BigDecimal("100"), Instant.now()),
                        new PriceTicker(Exchange.KRAKEN, "BTC/USD", "XXBTZUSD", "USD", new BigDecimal("101"), new BigDecimal("102"), Instant.now())
                ),
                "ETH/USD", List.of(
                        new PriceTicker(Exchange.BINANCE, "ETH/USD", "ETHUSD", "USD", new BigDecimal("2000"), new BigDecimal("2000"), Instant.now()),
                        new PriceTicker(Exchange.KRAKEN, "ETH/USD", "XETHZUSD", "USD", new BigDecimal("2020"), new BigDecimal("2030"), Instant.now())
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
            new PriceTicker(Exchange.BINANCE, "BTC/USD", "BTCUSD", "USD", new BigDecimal("0"), new BigDecimal("100"), Instant.now());
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new PriceTicker(Exchange.BINANCE, "BTC/USD", "BTCUSD", "USD", new BigDecimal("100"), new BigDecimal("-50"), Instant.now());
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new PriceTicker(Exchange.BINANCE, "BTC/USD", "BTCUSD", "USD", null, new BigDecimal("100"), Instant.now());
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new PriceTicker(Exchange.BINANCE, "BTC/USD", null, "USD", new BigDecimal("100"), new BigDecimal("100"), Instant.now());
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new PriceTicker(Exchange.BINANCE, "BTC/USD", "BTCUSD", null, new BigDecimal("100"), new BigDecimal("100"), Instant.now());
        });
    }

    @Test
    void testSameExchangeRoutesExcluded() {
        // If tickers come from the same exchange, no routes should be created
        Map<String, List<PriceTicker>> tickers = Map.of(
                "BTC/USD", List.of(
                        new PriceTicker(Exchange.BINANCE, "BTC/USD", "BTCUSD", "USD", new BigDecimal("100"), new BigDecimal("101"), Instant.now()),
                        new PriceTicker(Exchange.BINANCE, "BTC/USD", "BTCUSD", "USD", new BigDecimal("100"), new BigDecimal("101"), Instant.now())
                )
        );

        Map<Exchange, BigDecimal> fees = Map.of(
                Exchange.BINANCE, new BigDecimal("0.001")
        );

        SpreadCalculationService.CalculationResult result = service.calculateSpreads(tickers, fees);

        assertEquals(0, result.fullMatrix.size());
        assertEquals(0, result.bestPerSymbol.size());
    }

    @Test
    void testCalculateSpread_Kraken_to_Binance_reproducibleFromDisplayedNumbers() {
        // Prices in the same ballpark as the reported screenshot: Buy Kraken @ 64,967.30,
        // Sell Binance @ 64,963.00, Kraken fee 0.26%, Binance fee 0.1%.
        //
        // net% = ((sell * (1 - sellFee)) / (buy * (1 + buyFee)) - 1) * 100
        //      = ((64963.00 * 0.999) / (64967.30 * 1.0026) - 1) * 100
        //      = -0.3657% (independently computed from these exact inputs; the screenshot's
        //        -0.3675% came from a live, slightly different instant and is not reproducible
        //        bit-for-bit here — the point of this test is that the *formula* is verifiable
        //        from whatever numbers the UI displays, not that this literal fixture matches
        //        a screenshot taken at a different moment)

        PriceTicker buyTicker = new PriceTicker(
                Exchange.KRAKEN,
                "BTC/USD",
                "XXBTZUSD",
                "USD",
                new BigDecimal("64961.00"),  // bid
                new BigDecimal("64967.30"),  // ask (use this for buying)
                Instant.now()
        );

        PriceTicker sellTicker = new PriceTicker(
                Exchange.BINANCE,
                "BTC/USD",
                "BTCUSD",
                "USD",
                new BigDecimal("64963.00"),  // bid (use this for selling)
                new BigDecimal("64968.00"),  // ask
                Instant.now()
        );

        Map<Exchange, BigDecimal> fees = Map.of(
                Exchange.KRAKEN, new BigDecimal("0.0026"),
                Exchange.BINANCE, new BigDecimal("0.001")
        );

        var result = service.calculateSpreads(
                Map.of("BTC/USD", List.of(buyTicker, sellTicker)),
                fees
        );

        var opportunity = result.fullMatrix.stream()
                .filter(o -> o.buyExchange == Exchange.KRAKEN && o.sellExchange == Exchange.BINANCE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Kraken->Binance opportunity not found"));

        // Assert the exact net spread independently derived from these inputs above
        assertEquals(new BigDecimal("-0.3657"),
                opportunity.netSpreadPercent.setScale(4, java.math.RoundingMode.HALF_UP),
                "Net spread should be -0.3675%");

        // Verify the native symbols are threaded through
        assertEquals("XXBTZUSD", opportunity.buyNativeSymbol);
        assertEquals("BTCUSD", opportunity.sellNativeSymbol);
        assertEquals("USD", opportunity.buyQuoteAsset);
        assertEquals("USD", opportunity.sellQuoteAsset);
    }

    @Test
    void testLiquidityFieldsThreadedThrough() {
        PriceTicker buyTicker = new PriceTicker(
                Exchange.BINANCE,
                "BTC/USDT",
                "BTCUSDT",
                "USDT",
                new BigDecimal("100"),
                new BigDecimal("101"),
                Instant.now(),
                new BigDecimal("2.5"),
                new BigDecimal("1.0"),
                null
        );
        PriceTicker sellTicker = new PriceTicker(
                Exchange.KRAKEN,
                "BTC/USDT",
                "XBTUSDT",
                "USDT",
                new BigDecimal("102"),
                new BigDecimal("103"),
                Instant.now(),
                new BigDecimal("3.0"),
                new BigDecimal("0.8"),
                new BigDecimal("5000000")
        );

        var result = service.calculateSpreads(
                Map.of("BTC/USDT", List.of(buyTicker, sellTicker)),
                Map.of(Exchange.BINANCE, BigDecimal.ZERO, Exchange.KRAKEN, BigDecimal.ZERO)
        );

        var opp = result.fullMatrix.stream()
                .filter(o -> o.buyExchange == Exchange.BINANCE && o.sellExchange == Exchange.KRAKEN)
                .findFirst()
                .orElseThrow();

        assertEquals(new BigDecimal("1.0"), opp.buyAskSize);
        assertEquals(new BigDecimal("3.0"), opp.sellBidSize);
        assertNull(opp.buyQuoteVolume24h);
        assertEquals(new BigDecimal("5000000"), opp.sellQuoteVolume24h);
    }

    /**
     * The invariant the whole USD/USDT split exists to guarantee: no route in the matrix ever
     * pairs a USD leg with a USDT leg. calculateSpreads groups tickers by internal symbol
     * ("BTC/USD" vs "BTC/USDT" are different map keys), so this is structural — but it's the one
     * guarantee that must never regress silently, so it's asserted explicitly rather than assumed.
     */
    @Test
    void testCrossQuoteAssetRoutesNeverMix() {
        Map<String, List<PriceTicker>> tickers = Map.of(
                "BTC/USD", List.of(
                        new PriceTicker(Exchange.BINANCE, "BTC/USD", "BTCUSD", "USD", new BigDecimal("99"), new BigDecimal("100"), Instant.now()),
                        new PriceTicker(Exchange.KRAKEN, "BTC/USD", "XXBTZUSD", "USD", new BigDecimal("101"), new BigDecimal("102"), Instant.now())
                ),
                "BTC/USDT", List.of(
                        new PriceTicker(Exchange.BITGET, "BTC/USDT", "BTCUSDT", "USDT", new BigDecimal("99"), new BigDecimal("100"), Instant.now()),
                        new PriceTicker(Exchange.KUCOIN, "BTC/USDT", "BTC-USDT", "USDT", new BigDecimal("101"), new BigDecimal("102"), Instant.now())
                )
        );

        Map<Exchange, BigDecimal> fees = Map.of(
                Exchange.BINANCE, new BigDecimal("0.001"),
                Exchange.KRAKEN, new BigDecimal("0.001"),
                Exchange.BITGET, new BigDecimal("0.001"),
                Exchange.KUCOIN, new BigDecimal("0.001")
        );

        SpreadCalculationService.CalculationResult result = service.calculateSpreads(tickers, fees);

        // 2 routes per symbol (Binance<->Kraken, Bitget<->KuCoin) — never 4x4=16 cross-mixed
        assertEquals(4, result.fullMatrix.size());

        for (var opp : result.fullMatrix) {
            assertEquals(opp.buyQuoteAsset, opp.sellQuoteAsset,
                    "Route " + opp.buyExchange + "->" + opp.sellExchange + " mixed quote assets: "
                            + opp.buyQuoteAsset + " vs " + opp.sellQuoteAsset);
        }

        // Best-per-symbol never crosses either
        assertEquals(2, result.bestPerSymbol.size());
        assertEquals("USD", result.bestPerSymbol.get("BTC/USD").buyQuoteAsset);
        assertEquals("USDT", result.bestPerSymbol.get("BTC/USDT").buyQuoteAsset);
    }

    @Test
    void testManySymbols_noCrossSymbolContamination() {
        Map<String, List<PriceTicker>> tickers = new LinkedHashMap<>();
        String[] symbols = {"BTC/USD", "ETH/USD", "SOL/USD", "XRP/USD", "DOGE/USD",
                "BTC/USDT", "ETH/USDT", "SOL/USDT", "XRP/USDT", "DOGE/USDT", "BNB/USDT"};

        for (String symbol : symbols) {
            tickers.put(symbol, List.of(
                    new PriceTicker(Exchange.BINANCE, symbol, "N1", symbol.contains("USDT") ? "USDT" : "USD",
                            new BigDecimal("100"), new BigDecimal("101"), Instant.now()),
                    new PriceTicker(Exchange.KRAKEN, symbol, "N2", symbol.contains("USDT") ? "USDT" : "USD",
                            new BigDecimal("102"), new BigDecimal("103"), Instant.now())
            ));
        }

        Map<Exchange, BigDecimal> fees = Map.of(
                Exchange.BINANCE, new BigDecimal("0.001"),
                Exchange.KRAKEN, new BigDecimal("0.001")
        );

        SpreadCalculationService.CalculationResult result = service.calculateSpreads(tickers, fees);

        assertEquals(symbols.length, result.bestPerSymbol.size());
        for (var opp : result.fullMatrix) {
            assertEquals(opp.symbol, opp.buyQuoteAsset.equals("USDT") || opp.buyQuoteAsset.equals("USD")
                    ? opp.symbol.split("/")[0] + "/" + opp.buyQuoteAsset : opp.symbol);
            assertEquals(opp.buyQuoteAsset, opp.sellQuoteAsset);
        }
        for (String symbol : symbols) {
            assertTrue(result.bestPerSymbol.containsKey(symbol));
            assertEquals(symbol, result.bestPerSymbol.get(symbol).symbol);
        }
    }
}
