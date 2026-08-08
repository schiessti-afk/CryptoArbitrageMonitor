package com.cryptoarbitrage.monitor.config;

import com.cryptoarbitrage.monitor.exchange.Exchange;
import com.cryptoarbitrage.monitor.service.ExchangeBackoffStore;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.WriteTimeoutException;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.util.concurrent.TimeoutException;

/**
 * Records per-exchange backoff when a venue returns HTTP 429/418 or the request times out.
 * Does not alter the response — adapters still degrade to empty and the next poll cycle skips
 * the venue via {@link ExchangeBackoffStore#isBackingOff}.
 */
public final class ExchangeBackoffFilter {

    private ExchangeBackoffFilter() {
    }

    public static ExchangeFilterFunction create(Exchange exchange, ExchangeBackoffStore store) {
        return (request, next) -> next.exchange(request)
                .doOnNext(response -> recordIfRateLimited(exchange, store, response))
                .doOnError(error -> {
                    if (isTimeout(error)) {
                        store.recordTimeout(exchange);
                    }
                });
    }

    private static void recordIfRateLimited(Exchange exchange, ExchangeBackoffStore store, ClientResponse response) {
        int code = response.statusCode().value();
        if (code == 429 || code == 418) {
            store.recordRateLimit(exchange);
        }
    }

    static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current instanceof ReadTimeoutException
                    || current instanceof WriteTimeoutException
                    || current instanceof PrematureCloseException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("timeout") || lower.contains("timed out")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
