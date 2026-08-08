package com.cryptoarbitrage.monitor.exchange;

import com.cryptoarbitrage.monitor.config.ExchangeProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds an {@link ExchangeProperties} instance with the nested {@code markets} shape for adapter
 * unit tests, without needing Spring's {@code @ConfigurationProperties} binding.
 */
final class TestExchangeProperties {

    private TestExchangeProperties() {
    }

    /**
     * @param adapterKey   lowercase adapter name (e.g. "kraken", "bitget", "kucoin")
     * @param baseUrl      base URL (unused by adapter tests that stub the WebClient, but required
     *                     by the shape)
     * @param marketsByConfigKey map of config key (e.g. "BTC_USD") -> [nativeSymbol, quoteAsset]
     */
    static ExchangeProperties singleAdapter(String adapterKey, String baseUrl, Map<String, String[]> marketsByConfigKey) {
        ExchangeProperties.ExchangeConfig config = new ExchangeProperties.ExchangeConfig();
        config.setBaseUrl(baseUrl);

        Map<String, ExchangeProperties.MarketConfig> markets = new HashMap<>();
        for (var entry : marketsByConfigKey.entrySet()) {
            ExchangeProperties.MarketConfig market = new ExchangeProperties.MarketConfig();
            market.setNativeSymbol(entry.getValue()[0]);
            market.setQuoteAsset(entry.getValue()[1]);
            markets.put(entry.getKey(), market);
        }
        config.setMarkets(markets);

        ExchangeProperties properties = new ExchangeProperties();
        properties.setAdapters(Map.of(adapterKey, config));
        return properties;
    }
}
