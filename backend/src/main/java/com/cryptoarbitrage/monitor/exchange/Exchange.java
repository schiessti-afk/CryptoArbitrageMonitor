package com.cryptoarbitrage.monitor.exchange;

public enum Exchange {
    BINANCE,
    KRAKEN,
    COINBASE,
    BITGET,
    KUCOIN;

    @Override
    public String toString() {
        return this.name();
    }
}
