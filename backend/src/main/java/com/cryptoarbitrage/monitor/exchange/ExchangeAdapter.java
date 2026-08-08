package com.cryptoarbitrage.monitor.exchange;

import reactor.core.publisher.Mono;

/**
 * Contract for fetching normalized price tickers from an exchange.
 * Adapters are responsible for:
 * - Making HTTP calls to the exchange
 * - Handling exchange-specific response formats
 * - Mapping native symbols to internal format (e.g., BTCUSD -> BTC/USD)
 * - Converting responses into PriceTicker
 * - Error handling and graceful degradation
 */
public interface ExchangeAdapter {

    /**
     * @return the exchange this adapter represents
     */
    Exchange getExchange();

    /**
     * Whether this venue lists the given internal symbol at all (e.g. Bitget and KuCoin do not
     * list "BTC/USD" — USDT-only). Callers must check this before polling: an unlisted market is
     * not a failure and must never be treated like one (no ticker request, no availability-store
     * update, no WARN log). Backed by presence in {@code exchange.adapters.<name>.markets} —
     * see {@link com.cryptoarbitrage.monitor.config.ExchangeProperties}.
     *
     * @param internalSymbol the internal symbol format (e.g., "BTC/USD", "BTC/USDT")
     * @return true if this venue offers the symbol, false if it does not
     */
    boolean supports(String internalSymbol);

    /**
     * Fetch a price ticker for the given internal symbol (e.g., "BTC/USD").
     * Returns Mono.empty() on error so the cycle continues with other exchanges.
     * Callers should check {@link #supports(String)} first; behavior when called for an
     * unsupported symbol is adapter-defined (empty, not an error).
     *
     * @param internalSymbol the internal symbol format (e.g., "BTC/USD", "ETH/USD")
     * @return Mono emitting a PriceTicker on success, empty on error or unsupported symbol
     */
    Mono<PriceTicker> getTicker(String internalSymbol);
}
