package com.cryptoarbitrage.monitor.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoinbasePollSymbolResolverTest {

    private static final List<String> CORE = List.of(
            "BTC/USD", "ETH/USD", "SOL/USD", "XRP/USD", "DOGE/USD",
            "BTC/USDT", "ETH/USDT", "SOL/USDT", "XRP/USDT", "DOGE/USDT"
    );

    @Test
    void emptyInput_returnsEmpty() {
        assertTrue(CoinbasePollSymbolResolver.resolve(List.of(), s -> true, CORE, 8).isEmpty());
        assertTrue(CoinbasePollSymbolResolver.resolve(null, s -> true, CORE, 8).isEmpty());
        assertTrue(CoinbasePollSymbolResolver.resolve(List.of("BTC/USD"), s -> true, CORE, 0).isEmpty());
    }

    @Test
    void coreOnly_underCap_returnsAllEnabledCoreInCoreOrder() {
        List<String> enabled = List.of("DOGE/USDT", "BTC/USD", "ETH/USD");

        List<String> resolved = CoinbasePollSymbolResolver.resolve(enabled, CORE::contains, CORE, 8);

        assertEquals(List.of("BTC/USD", "ETH/USD", "DOGE/USDT"), resolved);
    }

    @Test
    void coreTruncated_whenMoreThanCapEnabledCore() {
        List<String> enabled = List.copyOf(CORE);

        List<String> resolved = CoinbasePollSymbolResolver.resolve(enabled, CORE::contains, CORE, 8);

        assertEquals(8, resolved.size());
        assertEquals(CORE.subList(0, 8), resolved);
    }

    @Test
    void extrasFillRemainingSlots_afterCore_inEnableOrder() {
        List<String> enabled = List.of(
                "BTC/USD", "ETH/USD", "SOL/USD", "XRP/USD", "DOGE/USD",
                "ADA/USDT", "LINK/USDT", "NEAR/USDT", "OP/USDT"
        );
        Set<String> supported = Set.of(
                "BTC/USD", "ETH/USD", "SOL/USD", "XRP/USD", "DOGE/USD",
                "ADA/USDT", "LINK/USDT", "NEAR/USDT", "OP/USDT"
        );

        List<String> resolved = CoinbasePollSymbolResolver.resolve(
                enabled, supported::contains, CORE.subList(0, 5), 8);

        assertEquals(List.of(
                "BTC/USD", "ETH/USD", "SOL/USD", "XRP/USD", "DOGE/USD",
                "ADA/USDT", "LINK/USDT", "NEAR/USDT"
        ), resolved);
    }

    @Test
    void extrasDropped_whenCoreFillsBudget() {
        List<String> enabled = List.of(
                "BTC/USD", "ETH/USD", "SOL/USD", "XRP/USD", "DOGE/USD",
                "BTC/USDT", "ETH/USDT", "SOL/USDT", "XRP/USDT", "DOGE/USDT",
                "ADA/USDT"
        );

        List<String> resolved = CoinbasePollSymbolResolver.resolve(enabled, CORE::contains, CORE, 8);

        assertEquals(CORE.subList(0, 8), resolved);
        assertTrue(resolved.stream().noneMatch("ADA/USDT"::equals));
    }

    @Test
    void unsupportedSymbols_areIgnored() {
        List<String> enabled = List.of("BTC/USD", "FAKE/USDT", "ADA/USDT");
        Set<String> supported = Set.of("BTC/USD", "ADA/USDT");

        List<String> resolved = CoinbasePollSymbolResolver.resolve(enabled, supported::contains, List.of("BTC/USD"), 8);

        assertEquals(List.of("BTC/USD", "ADA/USDT"), resolved);
    }

    @Test
    void nonCoreExtras_followEnableOrder_notAlphabetical() {
        List<String> enabled = List.of("BTC/USD", "NEAR/USDT", "ADA/USDT", "LINK/USDT");
        Set<String> supported = Set.copyOf(enabled);

        List<String> resolved = CoinbasePollSymbolResolver.resolve(
                enabled, supported::contains, List.of("BTC/USD"), 4);

        assertEquals(List.of("BTC/USD", "NEAR/USDT", "ADA/USDT", "LINK/USDT"), resolved);
    }
}
