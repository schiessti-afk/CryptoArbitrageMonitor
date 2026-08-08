package com.cryptoarbitrage.monitor.config;

import com.cryptoarbitrage.monitor.exchange.Exchange;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Binds {@code exchange.adapters.<name>.*} for every configured venue.
 *
 * A venue's {@code markets} map lists exactly the internal symbols (e.g. {@code BTC_USD},
 * {@code BTC_USDT}) it actually offers. Absence of an entry means the venue does not list that
 * market — this is the single source of truth {@link com.cryptoarbitrage.monitor.exchange.ExchangeAdapter#supports}
 * and {@link MarketConfigValidator} both read from, so adding or removing a market for a venue is
 * a config-only change.
 */
@Component
@ConfigurationProperties(prefix = "exchange")
public class ExchangeProperties {

    private Map<String, ExchangeConfig> adapters = new HashMap<>();

    public Map<String, ExchangeConfig> getAdapters() {
        return adapters;
    }

    public void setAdapters(Map<String, ExchangeConfig> adapters) {
        this.adapters = adapters;
    }

    /**
     * Distinct quote assets a venue offers at all (e.g. {@code {"USD", "USDT"}} for Binance,
     * {@code {"USDT"}} for Bitget). Used to filter which venues appear as status chips when the
     * frontend's quote-asset toggle is set to a value this venue doesn't offer.
     */
    public Set<String> getOfferedQuoteAssets(Exchange exchange) {
        ExchangeConfig config = adapters.get(exchange.name().toLowerCase());
        if (config == null) {
            return Set.of();
        }
        return config.getMarkets().values().stream()
                .map(MarketConfig::getQuoteAsset)
                .collect(Collectors.toSet());
    }

    /** How many configured venues list the given internal symbol. */
    public int countVenuesForSymbol(String internalSymbol) {
        int count = 0;
        for (ExchangeConfig config : adapters.values()) {
            if (config.getMarket(internalSymbol) != null) {
                count++;
            }
        }
        return count;
    }

    /** Distinct internal symbols configured across all venues. */
    public Set<String> getConfiguredSymbols() {
        return adapters.values().stream()
                .flatMap(config -> config.getMarkets().keySet().stream())
                .map(key -> key.replace("_", "/"))
                .collect(Collectors.toSet());
    }

    public static class ExchangeConfig {
        private String baseUrl;
        private long connectTimeoutMs = 5000;
        private long responseTimeoutMs = 10000;
        private Map<String, MarketConfig> markets = new HashMap<>();

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getResponseTimeoutMs() {
            return responseTimeoutMs;
        }

        public void setResponseTimeoutMs(long responseTimeoutMs) {
            this.responseTimeoutMs = responseTimeoutMs;
        }

        public Map<String, MarketConfig> getMarkets() {
            return markets;
        }

        public void setMarkets(Map<String, MarketConfig> markets) {
            this.markets = markets;
        }

        /**
         * Look up a market by internal symbol (e.g. "BTC/USD" -> config key "BTC_USD").
         */
        public MarketConfig getMarket(String internalSymbol) {
            return markets.get(internalSymbol.replace("/", "_"));
        }
    }

    public static class MarketConfig {
        private String nativeSymbol;
        private String quoteAsset;

        public String getNativeSymbol() {
            return nativeSymbol;
        }

        public void setNativeSymbol(String nativeSymbol) {
            this.nativeSymbol = nativeSymbol;
        }

        public String getQuoteAsset() {
            return quoteAsset;
        }

        public void setQuoteAsset(String quoteAsset) {
            this.quoteAsset = quoteAsset;
        }
    }
}
