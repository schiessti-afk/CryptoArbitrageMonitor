package com.cryptoarbitrage.monitor.exchange;

public enum Exchange {
    BINANCE,
    KRAKEN,
    COINBASE;

    @Override
    public String toString() {
        return this.name();
    }
}
