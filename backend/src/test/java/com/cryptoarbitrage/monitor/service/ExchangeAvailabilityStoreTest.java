package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.exchange.Exchange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExchangeAvailabilityStoreTest {

    /**
     * The bug this design prevents: a global "≥2 fresh exchanges" count can read LIVE while the
     * quote-asset universe actually being viewed has zero healthy venues (e.g. all three USD
     * venues down while Bitget and KuCoin, which only serve USDT, are fine). Freshness must be
     * computed per symbol, not per exchange globally.
     */
    @Test
    void testCountFreshForSymbol_IsIndependentAcrossQuoteAssets() {
        ExchangeAvailabilityStore store = new ExchangeAvailabilityStore();

        // Two USDT venues fresh...
        store.recordSuccess(Exchange.BITGET, "BTC/USDT");
        store.recordSuccess(Exchange.KUCOIN, "BTC/USDT");

        // ...but zero USD venues have ever reported.
        assertEquals(0, store.countFreshForSymbol("BTC/USD", 10_000));
        assertEquals(2, store.countFreshForSymbol("BTC/USDT", 10_000));
    }

    @Test
    void testIsFresh_PerExchangeAndSymbol() {
        ExchangeAvailabilityStore store = new ExchangeAvailabilityStore();
        store.recordSuccess(Exchange.BINANCE, "BTC/USDT");

        assertTrue(store.isFresh(Exchange.BINANCE, "BTC/USDT", 10_000));
        // Binance may be fresh on USDT while never having reported for USD at all.
        assertFalse(store.isFresh(Exchange.BINANCE, "BTC/USD", 10_000));
    }

    @Test
    void testIsFreshAny_TrueIfAnySymbolFresh() {
        ExchangeAvailabilityStore store = new ExchangeAvailabilityStore();
        store.recordSuccess(Exchange.BINANCE, "ETH/USDT");

        assertTrue(store.isFreshAny(Exchange.BINANCE, 10_000));
        assertFalse(store.isFreshAny(Exchange.KRAKEN, 10_000));
    }

    @Test
    void testGetLastReceivedAtAny_ReflectsMostRecentAcrossSymbols() {
        ExchangeAvailabilityStore store = new ExchangeAvailabilityStore();
        store.recordSuccess(Exchange.BINANCE, "BTC/USD");
        store.recordSuccess(Exchange.BINANCE, "BTC/USDT");

        assertNotNull(store.getLastReceivedAtAny(Exchange.BINANCE));
        assertNull(store.getLastReceivedAtAny(Exchange.COINBASE));
    }
}
