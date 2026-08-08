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
     * Fetch a price ticker for the given internal symbol (e.g., "BTC/USD").
     * Returns Mono.empty() on error so the cycle continues with other exchanges.
     *
     * @param internalSymbol the internal symbol format (e.g., "BTC/USD", "ETH/USD")
     * @return Mono emitting a PriceTicker on success, empty on error
     */
    Mono<PriceTicker> getTicker(String internalSymbol);
}
