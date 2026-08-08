package com.cryptoarbitrage.monitor.config;

import com.cryptoarbitrage.monitor.model.TrackedPair;
import com.cryptoarbitrage.monitor.repository.TrackedPairRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketConfigValidatorTest {

    @Mock
    private TrackedPairRepository trackedPairRepository;

    @Test
    void underCoveredSymbol_doesNotThrow() {
        ExchangeProperties properties = buildProperties(Map.of(
                "solo", Map.of("LONELY_USD", new String[]{"LONELYUSD", "USD"})
        ));
        when(trackedPairRepository.findByActiveTrue()).thenReturn(List.of(
                trackedPair("LONELY/USD")
        ));

        // Validator logs WARN at startup — construction must not throw.
        new MarketConfigValidator(properties, trackedPairRepository);
    }

    @Test
    void configDbDrift_doesNotThrow() {
        ExchangeProperties properties = buildProperties(Map.of(
                "binance", Map.of("BTC_USD", new String[]{"BTCUSD", "USD"})
        ));
        when(trackedPairRepository.findByActiveTrue()).thenReturn(List.of(
                trackedPair("ETH/USD")
        ));

        new MarketConfigValidator(properties, trackedPairRepository);
    }

    private static TrackedPair trackedPair(String symbol) {
        TrackedPair pair = new TrackedPair();
        pair.setSymbol(symbol);
        pair.setBaseCurrency(symbol.split("/")[0]);
        pair.setQuoteCurrency(symbol.split("/")[1]);
        pair.setActive(true);
        return pair;
    }

    private static ExchangeProperties buildProperties(Map<String, Map<String, String[]>> adapters) {
        ExchangeProperties properties = new ExchangeProperties();
        Map<String, ExchangeProperties.ExchangeConfig> adapterMap = new java.util.HashMap<>();
        for (var adapterEntry : adapters.entrySet()) {
            ExchangeProperties.ExchangeConfig config = new ExchangeProperties.ExchangeConfig();
            config.setBaseUrl("https://example.com");
            Map<String, ExchangeProperties.MarketConfig> markets = new java.util.HashMap<>();
            for (var marketEntry : adapterEntry.getValue().entrySet()) {
                ExchangeProperties.MarketConfig market = new ExchangeProperties.MarketConfig();
                market.setNativeSymbol(marketEntry.getValue()[0]);
                market.setQuoteAsset(marketEntry.getValue()[1]);
                markets.put(marketEntry.getKey(), market);
            }
            config.setMarkets(markets);
            adapterMap.put(adapterEntry.getKey(), config);
        }
        properties.setAdapters(adapterMap);
        return properties;
    }
}
