-- V6: Expand USDT universe by 19 base assets (second USDT batch)
--
-- Live-probed 2026-08-08: every row below has >=2 venues among Binance, Kraken, Bitget, KuCoin.
-- MATIC rebranded to POL on Bitget/KuCoin — tracked as POL/USDT (Binance lists both).
-- MKR/USDT skipped: Binance-only among configured venues (no cross-venue spread).
-- Coinbase optional USDT products (HBAR, STX, FET) are configured separately.

INSERT INTO tracked_pair (symbol, base_currency, quote_currency, active)
VALUES
    ('TRX/USDT',    'TRX',    'USDT', TRUE),
    ('POL/USDT',    'POL',    'USDT', TRUE),
    ('ETC/USDT',    'ETC',    'USDT', TRUE),
    ('ALGO/USDT',   'ALGO',   'USDT', TRUE),
    ('VET/USDT',    'VET',    'USDT', TRUE),
    ('ICP/USDT',    'ICP',    'USDT', TRUE),
    ('HBAR/USDT',   'HBAR',   'USDT', TRUE),
    ('SEI/USDT',    'SEI',    'USDT', TRUE),
    ('TIA/USDT',    'TIA',    'USDT', TRUE),
    ('STX/USDT',    'STX',    'USDT', TRUE),
    ('RUNE/USDT',   'RUNE',   'USDT', TRUE),
    ('JUP/USDT',    'JUP',    'USDT', TRUE),
    ('WLD/USDT',    'WLD',    'USDT', TRUE),
    ('FET/USDT',    'FET',    'USDT', TRUE),
    ('RENDER/USDT', 'RENDER', 'USDT', TRUE),
    ('TAO/USDT',    'TAO',    'USDT', TRUE),
    ('ENA/USDT',    'ENA',    'USDT', TRUE),
    ('ONDO/USDT',   'ONDO',   'USDT', TRUE),
    ('PENDLE/USDT', 'PENDLE', 'USDT', TRUE);
