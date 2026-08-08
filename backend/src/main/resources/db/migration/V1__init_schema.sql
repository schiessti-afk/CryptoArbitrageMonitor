-- V1: Core schema for crypto arbitrage monitoring

CREATE TABLE tracked_pair (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL UNIQUE,
    base_currency VARCHAR(10) NOT NULL,
    quote_currency VARCHAR(10) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE exchange_fee (
    id BIGSERIAL PRIMARY KEY,
    exchange VARCHAR(50) NOT NULL UNIQUE,
    taker_fee NUMERIC(12, 6) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE spread_log (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    buy_exchange VARCHAR(50) NOT NULL,
    sell_exchange VARCHAR(50) NOT NULL,
    buy_price NUMERIC(20, 8) NOT NULL,
    sell_price NUMERIC(20, 8) NOT NULL,
    raw_spread_percent NUMERIC(12, 6) NOT NULL,
    net_spread_percent NUMERIC(12, 6) NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Seed tracked pairs
INSERT INTO tracked_pair (symbol, base_currency, quote_currency, active)
VALUES
    ('BTC/USD', 'BTC', 'USD', TRUE),
    ('ETH/USD', 'ETH', 'USD', TRUE);

-- Seed exchange fees (configurable estimates, not live vendor rates)
INSERT INTO exchange_fee (exchange, taker_fee)
VALUES
    ('BINANCE', 0.001),      -- 0.1% taker fee
    ('KRAKEN', 0.0026),      -- 0.26% taker fee
    ('COINBASE', 0.006);     -- 0.6% taker fee
