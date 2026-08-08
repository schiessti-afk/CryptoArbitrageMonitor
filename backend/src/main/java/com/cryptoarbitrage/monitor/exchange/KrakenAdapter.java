package com.cryptoarbitrage.monitor.exchange;

import com.cryptoarbitrage.monitor.config.ExchangeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;

@Component
public class KrakenAdapter implements ExchangeAdapter {

    private static final Logger log = LoggerFactory.getLogger(KrakenAdapter.class);

    private final WebClient webClient;
    private final ExchangeProperties exchangeProperties;

    public KrakenAdapter(
            @Qualifier("krakenWebClient") WebClient webClient,
            ExchangeProperties exchangeProperties
    ) {
        this.webClient = webClient;
        this.exchangeProperties = exchangeProperties;
    }

    @Override
    public Exchange getExchange() {
        return Exchange.KRAKEN;
    }

    @Override
    public Mono<PriceTicker> getTicker(String internalSymbol) {
        // Map internal symbol to Kraken native symbol
        String nativeSymbol = mapToNativeSymbol(internalSymbol);
        if (nativeSymbol == null) {
            log.warn("Kraken: unknown internal symbol {}", internalSymbol);
            return Mono.empty();
        }

        return webClient.get()
                .uri("/0/public/Ticker?pair={pair}", nativeSymbol)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> {
                    log.warn("Kraken: HTTP {} for symbol {}", response.statusCode().value(), internalSymbol);
                    return Mono.error(new RuntimeException("HTTP " + response.statusCode().value()));
                })
                .bodyToMono(JsonNode.class)
                .map(json -> parseTicker(json, internalSymbol, nativeSymbol))
                .onErrorResume(e -> {
                    log.warn("Kraken: error fetching {}: {}", internalSymbol, e.getMessage());
                    return Mono.empty();
                });
    }

    private String mapToNativeSymbol(String internalSymbol) {
        ExchangeProperties.ExchangeConfig config = exchangeProperties.getAdapters().get("kraken");
        if (config == null) {
            return null;
        }
        return config.getSymbols().get(internalSymbol);
    }

    private PriceTicker parseTicker(JsonNode json, String internalSymbol, String nativeSymbol) {
        // Kraken returns { "error": [...], "result": {...} }
        if (json == null || json.isMissingNode()) {
            throw new IllegalArgumentException("Kraken response is missing or null");
        }

        // Check for errors
        JsonNode errorArray = json.get("error");
        if (errorArray != null && errorArray.isArray() && errorArray.size() > 0) {
            throw new IllegalArgumentException("Kraken error: " + errorArray.get(0).asText());
        }

        JsonNode result = json.get("result");
        if (result == null || !result.isObject() || result.size() == 0) {
            throw new IllegalArgumentException("Kraken: missing or empty result");
        }

        // Response key is typically the native symbol (e.g., XXBTZUSD)
        JsonNode ticker = result.get(nativeSymbol);
        if (ticker == null || ticker.isMissingNode()) {
            throw new IllegalArgumentException("Kraken: ticker not found for " + nativeSymbol);
        }

        // Kraken format: "a": [ask, ...], "b": [bid, ...]
        JsonNode askArray = ticker.get("a");
        JsonNode bidArray = ticker.get("b");

        if (askArray == null || askArray.size() < 1 || bidArray == null || bidArray.size() < 1) {
            throw new IllegalArgumentException("Kraken: missing ask or bid data");
        }

        BigDecimal ask = new BigDecimal(askArray.get(0).asText());
        BigDecimal bid = new BigDecimal(bidArray.get(0).asText());

        if (bid.signum() <= 0 || ask.signum() <= 0) {
            throw new IllegalArgumentException("Kraken: invalid bid/ask prices");
        }

        return new PriceTicker(
                Exchange.KRAKEN,
                internalSymbol,
                bid,
                ask,
                Instant.now()
        );
    }
}
