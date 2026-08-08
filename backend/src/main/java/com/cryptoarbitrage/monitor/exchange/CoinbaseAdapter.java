package com.cryptoarbitrage.monitor.exchange;

import com.cryptoarbitrage.monitor.config.ExchangeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
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
    public boolean supports(String internalSymbol) {
        return market(internalSymbol) != null;
    }

    @Override
    public Mono<PriceTicker> getTicker(String internalSymbol) {
        ExchangeProperties.MarketConfig market = market(internalSymbol);
        if (market == null) {
            // Not offered by this venue — not a failure, nothing to poll or log.
            return Mono.empty();
        }
        String nativeSymbol = market.getNativeSymbol();

        return webClient.get()
                .uri("/products/{productId}/ticker", nativeSymbol)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> {
                    log.warn("Coinbase: HTTP {} for symbol {}", response.statusCode().value(), nativeSymbol);
                    return Mono.error(new RuntimeException("HTTP " + response.statusCode().value()));
                })
                .bodyToMono(String.class)
                .map(this::parseJson)
                .map(json -> parseTicker(json, internalSymbol, market))
                .onErrorResume(e -> {
                    log.warn("Coinbase: error fetching {}: {}", internalSymbol, e.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Flux<PriceTicker> getTickers(java.util.Collection<String> internalSymbols) {
        return Flux.fromIterable(internalSymbols)
                .flatMap(this::getTicker, 4);
    }

    private ExchangeProperties.MarketConfig market(String internalSymbol) {
        ExchangeProperties.ExchangeConfig config = exchangeProperties.getAdapters().get("coinbase");
        if (config == null) {
            return null;
        }
        return config.getMarket(internalSymbol);
    }

    private JsonNode parseJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Coinbase: failed to parse response JSON", e);
        }
    }

    private PriceTicker parseTicker(JsonNode json, String internalSymbol, ExchangeProperties.MarketConfig market) {
        if (json == null || json.isMissingNode()) {
            throw new IllegalArgumentException("Coinbase response is missing or null");
        }

        // Coinbase format: "bid": "...", "ask": "..."
        BigDecimal bid = new BigDecimal(json.get("bid").asText());
        BigDecimal ask = new BigDecimal(json.get("ask").asText());

        if (bid.signum() <= 0 || ask.signum() <= 0) {
            throw new IllegalArgumentException("Coinbase: invalid bid/ask prices");
        }

        BigDecimal quoteVolume24h = null;
        BigDecimal volume = AdapterJsonUtils.optionalDecimal(json, "volume");
        BigDecimal price = AdapterJsonUtils.optionalDecimal(json, "price");
        if (volume != null && price != null) {
            quoteVolume24h = volume.multiply(price);
        }

        return new PriceTicker(
                Exchange.COINBASE,
                internalSymbol,
                market.getNativeSymbol(),
                market.getQuoteAsset(),
                bid,
                ask,
                Instant.now(),
                null,
                null,
                quoteVolume24h
        );
    }

    @Override
    public Mono<OrderBook> getOrderBook(String internalSymbol, int depth) {
        ExchangeProperties.MarketConfig market = market(internalSymbol);
        if (market == null) {
            return Mono.empty();
        }
        int limit = Math.max(1, Math.min(depth, 50));
        String nativeSymbol = market.getNativeSymbol();

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/products/{productId}/book")
                        .queryParam("level", 2)
                        .build(nativeSymbol))
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> {
                    log.warn("Coinbase: HTTP {} for depth {}", response.statusCode().value(), nativeSymbol);
                    return Mono.error(new RuntimeException("HTTP " + response.statusCode().value()));
                })
                .bodyToMono(String.class)
                .map(this::parseJson)
                .map(json -> new OrderBook(
                        Exchange.COINBASE,
                        internalSymbol,
                        nativeSymbol,
                        AdapterJsonUtils.parseLevels(json.get("bids"), limit),
                        AdapterJsonUtils.parseLevels(json.get("asks"), limit),
                        Instant.now()
                ))
                .onErrorResume(e -> {
                    log.warn("Coinbase: depth error for {}: {}", internalSymbol, e.getMessage());
                    return Mono.empty();
                });
    }
}
