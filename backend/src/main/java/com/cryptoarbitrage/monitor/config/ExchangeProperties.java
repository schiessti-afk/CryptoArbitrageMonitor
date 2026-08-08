package com.cryptoarbitrage.monitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "exchange")
public class ExchangeProperties {

    private ExchangeConfig binance = new ExchangeConfig();
    private ExchangeConfig kraken = new ExchangeConfig();
    private ExchangeConfig coinbase = new ExchangeConfig();

    public static class ExchangeConfig {
        private String baseUrl;
        private Map<String, String> symbols = new HashMap<>();
        private long connectTimeoutMs = 5000;
        private long responseTimeoutMs = 10000;

        // Getters and setters
        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Map<String, String> getSymbols() {
            return symbols;
        }

        public void setSymbols(Map<String, String> symbols) {
            this.symbols = symbols;
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
    }

    public ExchangeConfig getBinance() {
        return binance;
    }

    public void setBinance(ExchangeConfig binance) {
        this.binance = binance;
    }

    public ExchangeConfig getKraken() {
        return kraken;
    }

    public void setKraken(ExchangeConfig kraken) {
        this.kraken = kraken;
    }

    public ExchangeConfig getCoinbase() {
        return coinbase;
    }

    public void setCoinbase(ExchangeConfig coinbase) {
        this.coinbase = coinbase;
    }

    public Map<String, ExchangeConfig> getAdapters() {
        Map<String, ExchangeConfig> adapters = new HashMap<>();
        adapters.put("binance", binance);
        adapters.put("kraken", kraken);
        adapters.put("coinbase", coinbase);
        return adapters;
    }
}
