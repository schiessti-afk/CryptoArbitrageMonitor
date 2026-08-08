package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.config.AppProperties;
import com.cryptoarbitrage.monitor.exchange.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-exchange exponential backoff after HTTP 429/418 or request timeouts.
 * While an exchange is backing off, {@link PollOrchestrationService} skips it so we do not
 * keep hammering a rate-limited or unhealthy venue.
 */
@Component
public class ExchangeBackoffStore {

    private static final Logger log = LoggerFactory.getLogger(ExchangeBackoffStore.class);

    private final AppProperties appProperties;
    private final Clock clock;
    private final Map<Exchange, Instant> backoffUntil = new ConcurrentHashMap<>();
    private final Map<Exchange, Long> currentBackoffMs = new ConcurrentHashMap<>();

    /** Spring entry point — required because a package-visible test constructor also exists. */
    @Autowired
    public ExchangeBackoffStore(AppProperties appProperties) {
        this(appProperties, Clock.systemUTC());
    }

    /** Package-visible for tests that need a fixed clock. */
    ExchangeBackoffStore(AppProperties appProperties, Clock clock) {
        this.appProperties = appProperties;
        this.clock = clock;
    }

    public boolean isBackingOff(Exchange exchange) {
        Instant until = backoffUntil.get(exchange);
        return until != null && clock.instant().isBefore(until);
    }

    public Instant getBackoffUntil(Exchange exchange) {
        return backoffUntil.get(exchange);
    }

    public void recordRateLimit(Exchange exchange) {
        applyBackoff(exchange, "rate limit");
    }

    public void recordTimeout(Exchange exchange) {
        applyBackoff(exchange, "timeout");
    }

    /**
     * Reset the exponential multiplier after a clean success.
     * Does not cancel an active backoff window — a parallel Coinbase product fetch that
     * succeeds must not undo a 429 recorded for another product in the same cycle.
     */
    public void recordSuccess(Exchange exchange) {
        if (isBackingOff(exchange)) {
            return;
        }
        backoffUntil.remove(exchange);
        currentBackoffMs.remove(exchange);
    }

    private void applyBackoff(Exchange exchange, String reason) {
        long initialMs = appProperties.getPolling().getBackoffInitialMs();
        long maxMs = appProperties.getPolling().getBackoffMaxMs();
        long previous = currentBackoffMs.getOrDefault(exchange, 0L);
        long nextMs = previous <= 0 ? initialMs : Math.min(previous * 2, maxMs);
        currentBackoffMs.put(exchange, nextMs);
        Instant until = clock.instant().plusMillis(nextMs);
        backoffUntil.put(exchange, until);
        log.warn("Backing off {} for {}ms after {} (until {})", exchange, nextMs, reason, until);
    }
}
