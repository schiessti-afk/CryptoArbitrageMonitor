-- V5: Expand USDT universe by 20 base assets (Sprint — USDT market expansion)
--
-- Live-probed 2026-08-08: every row below has >=2 venues among Binance, Kraken, Bitget, KuCoin.
-- Binance symbols follow Bitget/KuCoin conventions (Binance API geo-blocked during probe).
-- Coinbase optional USDT products are configured separately; polling is gated by client settings.

INSERT INTO tracked_pair (symbol, base_currency, quote_currency, active)
VALUES
    ('ADA/USDT',  'ADA',  'USDT', TRUE),
    ('AVAX/USDT', 'AVAX', 'USDT', TRUE),
    ('LINK/USDT', 'LINK', 'USDT', TRUE),
    ('SUI/USDT',  'SUI',  'USDT', TRUE),
    ('DOT/USDT',  'DOT',  'USDT', TRUE),
    ('TON/USDT',  'TON',  'USDT', TRUE),
    ('LTC/USDT',  'LTC',  'USDT', TRUE),
    ('BCH/USDT',  'BCH',  'USDT', TRUE),
    ('SHIB/USDT', 'SHIB', 'USDT', TRUE),
    ('PEPE/USDT', 'PEPE', 'USDT', TRUE),
    ('UNI/USDT',  'UNI',  'USDT', TRUE),
    ('NEAR/USDT', 'NEAR', 'USDT', TRUE),
    ('APT/USDT',  'APT',  'USDT', TRUE),
    ('ATOM/USDT', 'ATOM', 'USDT', TRUE),
    ('FIL/USDT',  'FIL',  'USDT', TRUE),
    ('ARB/USDT',  'ARB',  'USDT', TRUE),
    ('OP/USDT',   'OP',   'USDT', TRUE),
    ('INJ/USDT',  'INJ',  'USDT', TRUE),
    ('AAVE/USDT', 'AAVE', 'USDT', TRUE),
    ('WIF/USDT',  'WIF',  'USDT', TRUE);
