package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.exchange.Exchange;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe holder for exchange availability status (last successful ticker received).
 */
@Component
public class ExchangeAvailabilityStore {

    private final Map<Exchange, Instant> lastReceivedAt = new ConcurrentHashMap<>();

    /**
     * Record a successful ticker receipt from an exchange.
     */
    public void recordSuccess(Exchange exchange) {
        lastReceivedAt.put(exchange, Instant.now());
    }

    /**
     * Get the last successful ticker time for an exchange, or null if never received.
     */
    public Instant getLastReceivedAt(Exchange exchange) {
        return lastReceivedAt.get(exchange);
    }

    /**
     * Get all exchanges' last received times.
     */
    public Map<Exchange, Instant> getAll() {
        return new HashMap<>(lastReceivedAt);
    }

    /**
     * Check if an exchange has fresh data within the given window (milliseconds).
     */
    public boolean isFresh(Exchange exchange, long freshnessWindowMs) {
        Instant lastTime = lastReceivedAt.get(exchange);
        if (lastTime == null) {
            return false;
        }
        long ageMs = System.currentTimeMillis() - lastTime.toEpochMilli();
        return ageMs < freshnessWindowMs;
    }

    /**
     * Count how many exchanges have fresh data.
     */
    public int countFresh(long freshnessWindowMs) {
        return (int) lastReceivedAt.keySet().stream()
                .filter(exchange -> isFresh(exchange, freshnessWindowMs))
                .count();
    }
}
