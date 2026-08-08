-- V3: Add the USDT quote-asset universe and two new venues (Bitget, KuCoin)
--
-- Bitget and KuCoin list BTC/ETH only against USDT, not USD (verified against their live public
-- APIs: Bitget's BTCUSD symbol returns HTTP 400 "Parameter BTCUSD does not exist"; KuCoin's
-- BTC-USD returns HTTP 200 with a null data payload). Rather than mixing USD and USDT prices in
-- one comparison, BTC/USDT and ETH/USDT are tracked as their own symbols — a separate quote-asset
-- universe with its own venue set, never blended with the BTC/USD and ETH/USD universe.

INSERT INTO tracked_pair (symbol, base_currency, quote_currency, active)
VALUES
    ('BTC/USDT', 'BTC', 'USDT', TRUE),
    ('ETH/USDT', 'ETH', 'USDT', TRUE);

-- Seed exchange fees for the two new venues (configurable estimates, not live vendor rates —
-- same caveat as the V1 seed data). Both listed at base-tier spot taker rate at time of writing;
-- verify against current published fee schedules before treating these as accurate long-term.
INSERT INTO exchange_fee (exchange, taker_fee)
VALUES
    ('BITGET', 0.001),   -- 0.1% spot taker, base tier
    ('KUCOIN', 0.001);   -- 0.1% spot taker, base tier
