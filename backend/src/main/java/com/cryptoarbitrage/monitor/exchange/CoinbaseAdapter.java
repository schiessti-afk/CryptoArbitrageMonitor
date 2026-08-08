package com.cryptoarbitrage.monitor.exchange;

import com.cryptoarbitrage.monitor.config.ExchangeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;

@Component
public class CoinbaseAdapter implements ExchangeAdapter {

    private static final Logger log = LoggerFactory.getLogger(CoinbaseAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final ExchangeProperties exchangeProperties;

    public CoinbaseAdapter(
            @Qualifier("coinbaseWebClient") WebClient webClient,
            ExchangeProperties exchangeProperties
    ) {
        this.webClient = webClient;
        this.exchangeProperties = exchangeProperties;
    }

    @Override
    public Exchange getExchange() {
        return Exchange.COINBASE;
    }

    @Override
    public Mono<PriceTicker> getTicker(String internalSymbol) {
        // Map internal symbol to Coinbase native symbol
        String nativeSymbol = mapToNativeSymbol(internalSymbol);
        if (nativeSymbol == null) {
            log.warn("Coinbase: unknown internal symbol {}", internalSymbol);
            return Mono.empty();
        }

        return webClient.get()
                .uri("/products/{productId}/ticker", nativeSymbol)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> {
                    log.warn("Coinbase: HTTP {} for symbol {}", response.statusCode().value(), nativeSymbol);
                    return Mono.error(new RuntimeException("HTTP " + response.statusCode().value()));
                })
                .bodyToMono(String.class)
                .map(this::parseJson)
                .map(json -> parseTicker(json, internalSymbol))
                .onErrorResume(e -> {
                    log.warn("Coinbase: error fetching {}: {}", internalSymbol, e.getMessage());
                    return Mono.empty();
                });
    }

    private String mapToNativeSymbol(String internalSymbol) {
        ExchangeProperties.ExchangeConfig config = exchangeProperties.getAdapters().get("coinbase");
        if (config == null) {
            return null;
        }
        // Convert BTC/USD → BTC_USD for config lookup
        String configKey = internalSymbol.replace("/", "_");
        return config.getSymbolMap().get(configKey);
    }

    private JsonNode parseJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Coinbase: failed to parse response JSON", e);
        }
    }

    private PriceTicker parseTicker(JsonNode json, String internalSymbol) {
        if (json == null || json.isMissingNode()) {
            throw new IllegalArgumentException("Coinbase response is missing or null");
        }

        // Coinbase format: "bid": "...", "ask": "..."
        BigDecimal bid = new BigDecimal(json.get("bid").asText());
        BigDecimal ask = new BigDecimal(json.get("ask").asText());

        if (bid.signum() <= 0 || ask.signum() <= 0) {
            throw new IllegalArgumentException("Coinbase: invalid bid/ask prices");
        }

        return new PriceTicker(
                Exchange.COINBASE,
                internalSymbol,
                bid,
                ask,
                Instant.now()
        );
    }
}
