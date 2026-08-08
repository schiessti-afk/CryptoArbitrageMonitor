-- V4: Expand tracked universe to SOL, XRP, DOGE and BNB (Sprint 3)
--
-- USD rows cover the three-venue USD universe (Binance, Kraken, Coinbase). Bitget and KuCoin
-- do not list USD spot markets for these assets.
--
-- BNB has no USD row: neither Kraken nor Coinbase lists a BNB/USD spot market suitable for
-- cross-venue comparison (Coinbase may list other BNB products; the Sprint 3 scope is BNB/USDT
-- on Binance, Bitget and KuCoin only). Do not add a BNB/USD tracked_pair without re-probing.

INSERT INTO tracked_pair (symbol, base_currency, quote_currency, active)
VALUES
    ('SOL/USD',  'SOL',  'USD',  TRUE),
    ('XRP/USD',  'XRP',  'USD',  TRUE),
    ('DOGE/USD', 'DOGE', 'USD',  TRUE),
    ('SOL/USDT',  'SOL',  'USDT', TRUE),
    ('XRP/USDT',  'XRP',  'USDT', TRUE),
    ('DOGE/USDT', 'DOGE', 'USDT', TRUE),
    ('BNB/USDT',  'BNB',  'USDT', TRUE);
