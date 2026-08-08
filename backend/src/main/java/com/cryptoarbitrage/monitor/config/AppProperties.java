package com.cryptoarbitrage.monitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Polling polling = new Polling();
    private Investment investment = new Investment();

    public static class Polling {
        private long intervalMs = 3000;
        private long freshnessWindowMs = 10000;
        /** USDT markets polled before the client sends preferences (matches frontend major-5 default). */
        private java.util.List<String> defaultUsdtMajors = java.util.List.of(
                "BTC/USDT", "ETH/USDT", "SOL/USDT", "XRP/USDT", "DOGE/USDT"
        );

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public long getFreshnessWindowMs() {
            return freshnessWindowMs;
        }

        public void setFreshnessWindowMs(long freshnessWindowMs) {
            this.freshnessWindowMs = freshnessWindowMs;
        }

        public java.util.List<String> getDefaultUsdtMajors() {
            return defaultUsdtMajors;
        }

        public void setDefaultUsdtMajors(java.util.List<String> defaultUsdtMajors) {
            this.defaultUsdtMajors = defaultUsdtMajors == null
                    ? java.util.List.of()
                    : defaultUsdtMajors;
        }
    }

    public static class Investment {
        private BigDecimal defaultNotional = new BigDecimal("1000");

        public BigDecimal getDefaultNotional() {
            return defaultNotional;
        }

        public void setDefaultNotional(BigDecimal defaultNotional) {
            this.defaultNotional = defaultNotional;
        }
    }

    public Polling getPolling() {
        return polling;
    }

    public void setPolling(Polling polling) {
        this.polling = polling;
    }

    public Investment getInvestment() {
        return investment;
    }

    public void setInvestment(Investment investment) {
        this.investment = investment;
    }
}
