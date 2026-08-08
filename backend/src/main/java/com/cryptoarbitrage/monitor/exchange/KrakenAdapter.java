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
public class KrakenAdapter implements ExchangeAdapter {

    private static final Logger log = LoggerFactory.getLogger(KrakenAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
                .uri("/0/public/Ticker?pair={pair}", nativeSymbol)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> {
                    log.warn("Kraken: HTTP {} for symbol {}", response.statusCode().value(), internalSymbol);
                    return Mono.error(new RuntimeException("HTTP " + response.statusCode().value()));
                })
                .bodyToMono(String.class)
                .map(this::parseJson)
                .map(json -> parseTicker(json, internalSymbol, market))
                .onErrorResume(e -> {
                    log.warn("Kraken: error fetching {}: {}", internalSymbol, e.getMessage());
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

        String pairsParam = supported.stream()
                .map(s -> marketByInternal.get(s).getNativeSymbol())
                .collect(Collectors.joining(","));

        return webClient.get()
                .uri("/0/public/Ticker?pair={pair}", pairsParam)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> {
                    log.warn("Kraken: HTTP {} for batch tickers", response.statusCode().value());
                    return Mono.error(new RuntimeException("HTTP " + response.statusCode().value()));
                })
                .bodyToMono(String.class)
                .map(this::parseJson)
                .flatMapMany(json -> parseBatchTickers(json, supported, marketByInternal, nativeToInternal))
                .onErrorResume(e -> {
                    log.warn("Kraken: batch ticker error: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    private Flux<PriceTicker> parseBatchTickers(
            JsonNode json,
            List<String> supported,
            Map<String, ExchangeProperties.MarketConfig> marketByInternal,
            Map<String, String> nativeToInternal
    ) {
        JsonNode errorArray = json.get("error");
        if (errorArray != null && errorArray.isArray() && !errorArray.isEmpty()) {
            return Flux.error(new IllegalArgumentException("Kraken error: " + errorArray.get(0).asText()));
        }

        JsonNode result = json.get("result");
        if (result == null || !result.isObject()) {
            return Flux.error(new IllegalArgumentException("Kraken: missing or empty result"));
        }

        Set<String> found = new HashSet<>();
        List<PriceTicker> tickers = new ArrayList<>();
        Iterator<String> fieldNames = result.fieldNames();
        while (fieldNames.hasNext()) {
            String responseKey = fieldNames.next();
            String internal = nativeToInternal.get(responseKey);
            if (internal != null) {
                found.add(internal);
                try {
                    tickers.add(parseTickerFromNode(result.get(responseKey), internal, marketByInternal.get(internal)));
                } catch (RuntimeException e) {
                    log.warn("Kraken: skipping {}: {}", internal, e.getMessage());
                }
            }
        }

        for (String symbol : supported) {
            if (!found.contains(symbol)) {
                log.warn("Kraken: symbol {} missing from batch response", symbol);
            }
        }

        return Flux.fromIterable(tickers);
    }

    private PriceTicker parseTickerFromNode(
            JsonNode ticker,
            String internalSymbol,
            ExchangeProperties.MarketConfig market
    ) {
        JsonNode askArray = ticker.get("a");
        JsonNode bidArray = ticker.get("b");

        if (askArray == null || askArray.isEmpty() || bidArray == null || bidArray.isEmpty()) {
            throw new IllegalArgumentException("Kraken: missing ask or bid data for " + internalSymbol);
        }

        BigDecimal ask = new BigDecimal(askArray.get(0).asText());
        BigDecimal bid = new BigDecimal(bidArray.get(0).asText());

        if (bid.signum() <= 0 || ask.signum() <= 0) {
            throw new IllegalArgumentException("Kraken: invalid bid/ask prices for " + internalSymbol);
        }

        return buildTicker(ticker, internalSymbol, market, bid, ask);
    }

    private PriceTicker buildTicker(
            JsonNode ticker,
            String internalSymbol,
            ExchangeProperties.MarketConfig market,
            BigDecimal bid,
            BigDecimal ask
    ) {
        BigDecimal bidSize = AdapterJsonUtils.optionalDecimalFromArray(ticker.get("b"), 1);
        BigDecimal askSize = AdapterJsonUtils.optionalDecimalFromArray(ticker.get("a"), 1);
        BigDecimal quoteVolume24h = null;
        JsonNode volumeArray = ticker.get("v");
        JsonNode lastArray = ticker.get("c");
        if (volumeArray != null && volumeArray.isArray() && volumeArray.size() > 1
                && lastArray != null && lastArray.isArray() && lastArray.size() > 0) {
            BigDecimal baseVolume24h = new BigDecimal(volumeArray.get(1).asText());
            BigDecimal lastPrice = new BigDecimal(lastArray.get(0).asText());
            quoteVolume24h = baseVolume24h.multiply(lastPrice);
        }

        return new PriceTicker(
                Exchange.KRAKEN,
                internalSymbol,
                market.getNativeSymbol(),
                market.getQuoteAsset(),
                bid,
                ask,
                Instant.now(),
                bidSize,
                askSize,
                quoteVolume24h
        );
    }

    private ExchangeProperties.MarketConfig market(String internalSymbol) {
        ExchangeProperties.ExchangeConfig config = exchangeProperties.getAdapters().get("kraken");
        if (config == null) {
            return null;
        }
        return config.getMarket(internalSymbol);
    }

    private JsonNode parseJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Kraken: failed to parse response JSON", e);
        }
    }

    private PriceTicker parseTicker(JsonNode json, String internalSymbol, ExchangeProperties.MarketConfig market) {
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

        String nativeSymbol = market.getNativeSymbol();

        // Response key is typically the native symbol (e.g., XXBTZUSD, or XBTUSDT for the USDT
        // market — Kraken does not apply its X../Z.. wrapping to USDT pairs).
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

        return buildTicker(ticker, internalSymbol, market, bid, ask);
    }

    @Override
    public Mono<OrderBook> getOrderBook(String internalSymbol, int depth) {
        ExchangeProperties.MarketConfig market = market(internalSymbol);
        if (market == null) {
            return Mono.empty();
        }
        int count = Math.max(1, Math.min(depth, 500));
        String nativeSymbol = market.getNativeSymbol();

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/0/public/Depth")
                        .queryParam("pair", nativeSymbol)
                        .queryParam("count", count)
                        .build())
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> {
                    log.warn("Kraken: HTTP {} for depth {}", response.statusCode().value(), nativeSymbol);
                    return Mono.error(new RuntimeException("HTTP " + response.statusCode().value()));
                })
                .bodyToMono(String.class)
                .map(this::parseJson)
                .map(json -> parseDepth(json, internalSymbol, market, count))
                .onErrorResume(e -> {
                    log.warn("Kraken: depth error for {}: {}", internalSymbol, e.getMessage());
                    return Mono.empty();
                });
    }

    private OrderBook parseDepth(
            JsonNode json,
            String internalSymbol,
            ExchangeProperties.MarketConfig market,
            int count
    ) {
        JsonNode errorArray = json.get("error");
        if (errorArray != null && errorArray.isArray() && !errorArray.isEmpty()) {
            throw new IllegalArgumentException("Kraken error: " + errorArray.get(0).asText());
        }

        JsonNode result = json.get("result");
        if (result == null || !result.isObject()) {
            throw new IllegalArgumentException("Kraken: missing depth result");
        }

        JsonNode book = result.get(market.getNativeSymbol());
        if (book == null || book.isMissingNode()) {
            throw new IllegalArgumentException("Kraken: depth not found for " + market.getNativeSymbol());
        }

        return new OrderBook(
                Exchange.KRAKEN,
                internalSymbol,
                market.getNativeSymbol(),
                AdapterJsonUtils.parseLevels(book.get("bids"), count),
                AdapterJsonUtils.parseLevels(book.get("asks"), count),
                Instant.now()
        );
    }
}
