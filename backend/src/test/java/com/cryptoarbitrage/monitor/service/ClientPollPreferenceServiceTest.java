package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPollPreferenceServiceTest {

    private ClientPollPreferenceService service;

    private static final List<String> ALL = List.of(
            "BTC/USD", "ETH/USD", "SOL/USD", "XRP/USD", "DOGE/USD",
            "BTC/USDT", "ETH/USDT", "SOL/USDT", "XRP/USDT", "DOGE/USDT",
            "ADA/USDT", "BNB/USDT"
    );

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        service = new ClientPollPreferenceService(props);
    }

    @Test
    void noPreference_defaultsToUsdAndMajorUsdt() {
        List<String> polled = service.resolvePollSymbols(ALL);

        assertTrue(polled.contains("BTC/USD"));
        assertTrue(polled.contains("BTC/USDT"));
        assertEquals(10, polled.size());
        assertTrue(polled.stream().noneMatch("ADA/USDT"::equals));
        assertTrue(polled.stream().noneMatch("BNB/USDT"::equals));
    }

    @Test
    void clientPreference_pollsOnlyEnabled() {
        service.updateEnabledSymbols(List.of("BTC/USDT", "ADA/USDT"));

        List<String> polled = service.resolvePollSymbols(ALL);

        assertEquals(List.of("ADA/USDT", "BTC/USDT"), polled);
    }

    @Test
    void emptyEnabledList_pollsNothingWhenClientSetPreference() {
        service.updateEnabledSymbols(List.of());

        List<String> polled = service.resolvePollSymbols(ALL);

        assertTrue(polled.isEmpty());
    }
}
