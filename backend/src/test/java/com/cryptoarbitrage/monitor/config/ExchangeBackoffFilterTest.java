package com.cryptoarbitrage.monitor.config;

import com.cryptoarbitrage.monitor.exchange.Exchange;
import com.cryptoarbitrage.monitor.service.ExchangeBackoffStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExchangeBackoffFilterTest {

    private AppProperties props;
    private ExchangeBackoffStore store;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getPolling().setBackoffInitialMs(15_000);
        props.getPolling().setBackoffMaxMs(120_000);
        store = new ExchangeBackoffStore(props);
    }

    @Test
    void recordsBackoffOnHttp429() {
        WebClient client = clientReturning(HttpStatus.TOO_MANY_REQUESTS, "rate limited");

        assertThrows(Exception.class, () ->
                client.get().uri("/ticker").retrieve().bodyToMono(String.class).block());

        assertTrue(store.isBackingOff(Exchange.BINANCE));
    }

    @Test
    void recordsBackoffOnHttp418() {
        WebClient client = clientReturning(HttpStatus.I_AM_A_TEAPOT, "banned");

        assertThrows(Exception.class, () ->
                client.get().uri("/ticker").retrieve().bodyToMono(String.class).block());

        assertTrue(store.isBackingOff(Exchange.BINANCE));
    }

    @Test
    void doesNotRecordBackoffOnHttp500() {
        WebClient client = clientReturning(HttpStatus.INTERNAL_SERVER_ERROR, "boom");

        assertThrows(Exception.class, () ->
                client.get().uri("/ticker").retrieve().bodyToMono(String.class).block());

        assertFalse(store.isBackingOff(Exchange.BINANCE));
    }

    @Test
    void recordsBackoffOnTimeout() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction failing = request -> {
            calls.incrementAndGet();
            return Mono.error(new TimeoutException("response timed out"));
        };

        WebClient client = WebClient.builder()
                .baseUrl("https://example.test")
                .filter(ExchangeBackoffFilter.create(Exchange.KRAKEN, store))
                .exchangeFunction(failing)
                .build();

        assertThrows(Exception.class, () ->
                client.get().uri("/ticker").retrieve().bodyToMono(String.class).block(Duration.ofSeconds(2)));

        assertEquals(1, calls.get());
        assertTrue(store.isBackingOff(Exchange.KRAKEN));
    }

    @Test
    void isTimeout_detectsNestedTimeoutMessage() {
        RuntimeException wrapped = new RuntimeException("upstream failed", new TimeoutException("timed out"));
        assertTrue(ExchangeBackoffFilter.isTimeout(wrapped));
        assertFalse(ExchangeBackoffFilter.isTimeout(new RuntimeException("bad request")));
    }

    private WebClient clientReturning(HttpStatus status, String body) {
        ExchangeFunction exchangeFunction = request ->
                Mono.just(ClientResponse.create(status)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build());

        return WebClient.builder()
                .baseUrl("https://api.binance.com")
                .filter(ExchangeBackoffFilter.create(Exchange.BINANCE, store))
                .exchangeFunction(exchangeFunction)
                .build();
    }
}
