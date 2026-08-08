package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.config.AppProperties;
import com.cryptoarbitrage.monitor.exchange.Exchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class ExchangeBackoffStoreTest {

    private AppProperties props;
    private MutableClock clock;
    private ExchangeBackoffStore store;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getPolling().setBackoffInitialMs(15_000);
        props.getPolling().setBackoffMaxMs(120_000);
        clock = new MutableClock(Instant.parse("2026-08-08T12:00:00Z"));
        store = new ExchangeBackoffStore(props, clock);
    }

    @Test
    void recordRateLimit_backsOffForInitialWindow() {
        store.recordRateLimit(Exchange.BINANCE);

        assertTrue(store.isBackingOff(Exchange.BINANCE));
        assertEquals(Instant.parse("2026-08-08T12:00:15Z"), store.getBackoffUntil(Exchange.BINANCE));
        assertFalse(store.isBackingOff(Exchange.KRAKEN));
    }

    @Test
    void backoff_expiresAfterWindow() {
        store.recordTimeout(Exchange.COINBASE);
        assertTrue(store.isBackingOff(Exchange.COINBASE));

        clock.advanceMillis(15_000);
        assertFalse(store.isBackingOff(Exchange.COINBASE));
    }

    @Test
    void repeatedFailures_doubleBackoffUpToMax() {
        store.recordRateLimit(Exchange.BITGET);
        assertEquals(Instant.parse("2026-08-08T12:00:15Z"), store.getBackoffUntil(Exchange.BITGET));

        store.recordRateLimit(Exchange.BITGET);
        assertEquals(Instant.parse("2026-08-08T12:00:30Z"), store.getBackoffUntil(Exchange.BITGET));

        store.recordRateLimit(Exchange.BITGET); // 60s
        store.recordRateLimit(Exchange.BITGET); // 120s
        store.recordRateLimit(Exchange.BITGET); // still capped at 120s
        assertEquals(Instant.parse("2026-08-08T12:02:00Z"), store.getBackoffUntil(Exchange.BITGET));
    }

    @Test
    void recordSuccess_doesNotCancelActiveBackoff() {
        store.recordRateLimit(Exchange.KUCOIN);
        assertTrue(store.isBackingOff(Exchange.KUCOIN));

        store.recordSuccess(Exchange.KUCOIN);
        assertTrue(store.isBackingOff(Exchange.KUCOIN));
    }

    @Test
    void recordSuccess_resetsMultiplierAfterBackoffExpires() {
        store.recordRateLimit(Exchange.BINANCE);
        store.recordRateLimit(Exchange.BINANCE); // 30s window
        clock.advanceMillis(30_000);
        assertFalse(store.isBackingOff(Exchange.BINANCE));

        store.recordSuccess(Exchange.BINANCE);
        store.recordRateLimit(Exchange.BINANCE); // should restart at initial 15s, not 60s
        assertEquals(Instant.parse("2026-08-08T12:00:45Z"), store.getBackoffUntil(Exchange.BINANCE));
    }

    /** Clock that can be advanced for backoff expiry tests. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceMillis(long ms) {
            instant = instant.plusMillis(ms);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
