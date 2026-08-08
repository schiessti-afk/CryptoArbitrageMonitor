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
import java.util.*;
import java.util.stream.Collectors;

@Component
public class BinanceAdapter implements ExchangeAdapter {

    private static final Logger log = LoggerFactory.getLogger(BinanceAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final ExchangeProperties exchangeProperties;

    public BinanceAdapter(
            @Qualifier("binanceWebClient") WebClient webClient,
            ExchangeProperties exchangeProperties
    ) {
        this.webClient = webClient;
        this.exchangeProperties = exchangeProperties;
    }

    @Override
    public Exchange getExchange() {
        return Exchange.BINANCE;
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
                .uri("/api/v3/ticker/bookTicker?symbol={symbol}", nativeSymbol)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> {
                    log.warn("Binance: HTTP {} for symbol {}", response.statusCode().value(), nativeSymbol);
                    return Mono.error(new RuntimeException("HTTP " + response.statusCode().value()));
                })
                .bodyToMono(String.class)
                .map(this::parseJson)
                .map(json -> parseTicker(json, internalSymbol, market))
                .onErrorResume(e -> {
                    log.warn("Binance: error fetching {}: {}", internalSymbol, e.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Flux<PriceTicker> getTickers(Collection<String> internalSymbols) {
        List<String> supported = internalSymbols.stream().filter(this::supports).toList();
        if (supported.isEmpty()) {
            return Flux.empty();
        }

        Map<String, ExchangeProperties.MarketConfig> marketByInternal = new LinkedHashMap<>();
        Map<String, String> nativeToInternal = new HashMap<>();
        for (String symbol : supported) {
            ExchangeProperties.MarketConfig market = market(symbol);
            marketByInternal.put(symbol, market);
            nativeToInternal.put(market.getNativeSymbol(), symbol);
        }

        String symbolsParam = supported.stream()
                .map(s -> "\"" + marketByInternal.get(s).getNativeSymbol() + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v3/ticker/bookTicker")
                        .queryParam("symbols", symbolsParam)
                        .build())
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> {
                    log.warn("Binance: HTTP {} for batch tickers", response.statusCode().value());
                    return Mono.error(new RuntimeException("HTTP " + response.statusCode().value()));
                })
                .bodyToMono(String.class)
                .map(this::parseJson)
                .flatMapMany(json -> {
                    if (!json.isArray()) {
                        return Flux.error(new IllegalArgumentException("Binance: expected array response"));
                    }
                    Set<String> found = new HashSet<>();
                    List<PriceTicker> tickers = new ArrayList<>();
                    for (JsonNode node : json) {
                        String nativeSymbol = node.get("symbol").asText();
                        String internal = nativeToInternal.get(nativeSymbol);
                        if (internal != null) {
                            found.add(internal);
                            try {
                                tickers.add(parseTicker(node, internal, marketByInternal.get(internal)));
                            } catch (RuntimeException e) {
                                // One halted/illiquid market (e.g. zero bid/ask) must not discard the batch.
                                log.warn("Binance: skipping {}: {}", internal, e.getMessage());
                            }
                        }
                    }
                    for (String symbol : supported) {
                        if (!found.contains(symbol)) {
                            log.warn("Binance: symbol {} missing from batch response", symbol);
                        }
                    }
                    return Flux.fromIterable(tickers);
                })
                .onErrorResume(e -> {
                    log.warn("Binance: batch ticker error: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    private ExchangeProperties.MarketConfig market(String internalSymbol) {
        ExchangeProperties.ExchangeConfig config = exchangeProperties.getAdapters().get("binance");
        if (config == null) {
            return null;
        }
        return config.getMarket(internalSymbol);
    }

    private JsonNode parseJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Binance: failed to parse response JSON", e);
        }
    }

    private PriceTicker parseTicker(JsonNode json, String internalSymbol, ExchangeProperties.MarketConfig market) {
        if (json == null || json.isMissingNode()) {
            throw new IllegalArgumentException("Binance response is missing or null");
        }

        BigDecimal bid = new BigDecimal(json.get("bidPrice").asText());
        BigDecimal ask = new BigDecimal(json.get("askPrice").asText());

        if (bid.signum() <= 0 || ask.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Binance: invalid bid/ask prices for " + internalSymbol + " (bid=" + bid + ", ask=" + ask + ")");
        }

        BigDecimal bidSize = AdapterJsonUtils.optionalDecimal(json, "bidQty");
        BigDecimal askSize = AdapterJsonUtils.optionalDecimal(json, "askQty");

        return new PriceTicker(
                Exchange.BINANCE,
                internalSymbol,
                market.getNativeSymbol(),
                market.getQuoteAsset(),
                bid,
                ask,
                Instant.now(),
                bidSize,
                askSize,
                null
        );
    }

    @Override
    public Mono<OrderBook> getOrderBook(String internalSymbol, int depth) {
        ExchangeProperties.MarketConfig market = market(internalSymbol);
        if (market == null) {
            return Mono.empty();
        }
        int limit = Math.max(1, Math.min(depth, 100));
        String nativeSymbol = market.getNativeSymbol();

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v3/depth")
                        .queryParam("symbol", nativeSymbol)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> {
                    log.warn("Binance: HTTP {} for depth {}", response.statusCode().value(), nativeSymbol);
                    return Mono.error(new RuntimeException("HTTP " + response.statusCode().value()));
                })
                .bodyToMono(String.class)
                .map(this::parseJson)
                .map(json -> new OrderBook(
                        Exchange.BINANCE,
                        internalSymbol,
                        nativeSymbol,
                        AdapterJsonUtils.parseLevels(json.get("bids"), limit),
                        AdapterJsonUtils.parseLevels(json.get("asks"), limit),
                        Instant.now()
                ))
                .onErrorResume(e -> {
                    log.warn("Binance: depth error for {}: {}", internalSymbol, e.getMessage());
                    return Mono.empty();
                });
    }
}
