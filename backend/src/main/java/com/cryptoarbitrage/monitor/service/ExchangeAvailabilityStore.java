package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.exchange.Exchange;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe holder for exchange availability status, keyed per (exchange, symbol).
 *
 * <p>A single venue can be healthy on one market and failing on another (e.g. Binance fresh on
 * BTC/USDT while its BTC/USD feed times out), so freshness is not a per-exchange fact — it is a
 * per-(exchange, symbol) fact. The per-exchange convenience methods below aggregate across
 * whatever symbols that exchange has actually reported for, and exist for the "is this venue
 * alive at all" chip in {@code /api/exchanges}; per-quote-asset LIVE computation in
 * {@link SpreadPublisher} uses the per-symbol methods directly.</p>
 */
@Component
public class ExchangeAvailabilityStore {

    private record Key(Exchange exchange, String symbol) {
    }

    private final Map<Key, Instant> lastReceivedAt = new ConcurrentHashMap<>();

    /**
     * Record a successful ticker receipt from an exchange for a specific symbol.
     */
    public void recordSuccess(Exchange exchange, String symbol) {
        lastReceivedAt.put(new Key(exchange, symbol), Instant.now());
    }

    /**
     * Last successful ticker time for one (exchange, symbol) pair, or null if never received.
     */
    public Instant getLastReceivedAt(Exchange exchange, String symbol) {
        return lastReceivedAt.get(new Key(exchange, symbol));
    }

    /**
     * Whether (exchange, symbol) has fresh data within the given window (milliseconds).
     */
    public boolean isFresh(Exchange exchange, String symbol, long freshnessWindowMs) {
        return isFreshInstant(lastReceivedAt.get(new Key(exchange, symbol)), freshnessWindowMs);
    }

    /**
     * How many distinct exchanges have fresh data for a given symbol — the input to per-quote-asset
     * LIVE computation (≥2 required to form any spread route).
     */
    public int countFreshForSymbol(String symbol, long freshnessWindowMs) {
        return (int) lastReceivedAt.entrySet().stream()
                .filter(e -> e.getKey().symbol().equals(symbol))
                .filter(e -> isFreshInstant(e.getValue(), freshnessWindowMs))
                .map(e -> e.getKey().exchange())
                .distinct()
                .count();
    }

    /**
     * Most recent successful receipt across every symbol this exchange has ever reported for.
     * Used for the venue-level "last update" display — a market it doesn't offer never
     * contributes an entry here (see ExchangeAdapter#supports), so this only reflects markets
     * actually polled.
     */
    public Instant getLastReceivedAtAny(Exchange exchange) {
        return lastReceivedAt.entrySet().stream()
                .filter(e -> e.getKey().exchange() == exchange)
                .map(Map.Entry::getValue)
                .max(Instant::compareTo)
                .orElse(null);
    }

    /**
     * Whether an exchange has fresh data for at least one symbol it offers.
     */
    public boolean isFreshAny(Exchange exchange, long freshnessWindowMs) {
        return lastReceivedAt.entrySet().stream()
                .filter(e -> e.getKey().exchange() == exchange)
                .anyMatch(e -> isFreshInstant(e.getValue(), freshnessWindowMs));
    }

    /**
     * All symbols this exchange has ever successfully reported for.
     */
    public Set<String> getKnownSymbols(Exchange exchange) {
        return lastReceivedAt.keySet().stream()
                .filter(k -> k.exchange() == exchange)
                .map(Key::symbol)
                .collect(java.util.stream.Collectors.toSet());
    }

    private boolean isFreshInstant(Instant lastTime, long freshnessWindowMs) {
        if (lastTime == null) {
            return false;
        }
        long ageMs = System.currentTimeMillis() - lastTime.toEpochMilli();
        return ageMs < freshnessWindowMs;
    }
}
