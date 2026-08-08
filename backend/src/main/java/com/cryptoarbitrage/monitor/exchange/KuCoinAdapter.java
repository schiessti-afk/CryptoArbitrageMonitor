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

/**
 * KuCoin level-1 orderbook ticker. USDT-only for BTC/ETH — no BTC/USD or ETH/USD market exists.
 * {@link #supports(String)} reflects that via config presence, so this adapter is never polled
 * for a USD market.
 *
 * <p><b>Important gotcha (verified live):</b> an unknown symbol does NOT return a non-2xx HTTP
 * status. It returns HTTP 200 with {@code {"code":"200000","data":null}}. The standard
 * {@code onStatus} failure check cannot catch this — {@link #parseTicker} must explicitly check
 * for a null {@code data} node, or a {@link com.fasterxml.jackson.databind.node.NullNode} field
 * access downstream throws an unguarded NPE instead of a clean adapter error.</p>
 */
@Component
public class KuCoinAdapter implements ExchangeAdapter {

    private static final Logger log = LoggerFactory.getLogger(KuCoinAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final ExchangeProperties exchangeProperties;

    public KuCoinAdapter(
            @Qualifier("kucoinWebClient") WebClient webClient,
            ExchangeProperties exchangeProperties
    ) {
        this.webClient = webClient;
        this.exchangeProperties = exchangeProperties;
    }

    @Override
    public Exchange getExchange() {
        return Exchange.KUCOIN;
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
                .uri("/api/v1/market/orderbook/level1?symbol={symbol}", nativeSymbol)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> {
                    log.warn("KuCoin: HTTP {} for symbol {}", response.statusCode().value(), nativeSymbol);
                    return Mono.error(new RuntimeException("HTTP " + response.statusCode().value()));
                })
                .bodyToMono(String.class)
                .map(this::parseJson)
                .map(json -> parseTicker(json, internalSymbol, market))
                .onErrorResume(e -> {
                    log.warn("KuCoin: error fetching {}: {}", internalSymbol, e.getMessage());
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
        Set<String> wantedNative = new HashSet<>();
        for (String symbol : supported) {
            ExchangeProperties.MarketConfig market = market(symbol);
            marketByInternal.put(symbol, market);
            wantedNative.add(market.getNativeSymbol());
        }

        return webClient.get()
                .uri("/api/v1/market/allTickers")
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> {
                    log.warn("KuCoin: HTTP {} for batch tickers", response.statusCode().value());
                    return Mono.error(new RuntimeException("HTTP " + response.statusCode().value()));
                })
                .bodyToMono(String.class)
                .map(this::parseJson)
                .flatMapMany(json -> parseBatchTickers(json, supported, marketByInternal, wantedNative))
                .onErrorResume(e -> {
                    log.warn("KuCoin: batch ticker error: {}", e.getMessage());
                    return Flux.empty();
                });
    }

    private Flux<PriceTicker> parseBatchTickers(
            JsonNode json,
            List<String> supported,
            Map<String, ExchangeProperties.MarketConfig> marketByInternal,
            Set<String> wantedNative
    ) {
        JsonNode dataWrapper = json.get("data");
        if (dataWrapper == null || dataWrapper.isNull() || dataWrapper.isMissingNode()) {
            return Flux.error(new IllegalArgumentException("KuCoin: no data in allTickers response"));
        }

        JsonNode tickers = dataWrapper.get("ticker");
        if (tickers == null || !tickers.isArray()) {
            return Flux.error(new IllegalArgumentException("KuCoin: missing ticker array"));
        }

        Map<String, String> nativeToInternal = supported.stream()
                .collect(Collectors.toMap(
                        s -> marketByInternal.get(s).getNativeSymbol(),
                        s -> s,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        Set<String> found = new HashSet<>();
        List<PriceTicker> results = new ArrayList<>();
        for (JsonNode node : tickers) {
            String nativeSymbol = node.get("symbol").asText();
            if (!wantedNative.contains(nativeSymbol)) {
                continue;
            }
            String internal = nativeToInternal.get(nativeSymbol);
            if (internal != null) {
                found.add(internal);
                results.add(parseTickerNode(node, internal, marketByInternal.get(internal)));
            }
        }

        for (String symbol : supported) {
            if (!found.contains(symbol)) {
                log.warn("KuCoin: symbol {} missing from batch response", symbol);
            }
        }

        return Flux.fromIterable(results);
    }

    private PriceTicker parseTickerNode(
            JsonNode data,
            String internalSymbol,
            ExchangeProperties.MarketConfig market
    ) {
        JsonNode bestBid = data.get("buy");
        JsonNode bestAsk = data.get("sell");
        if (bestBid == null || bestBid.isNull() || bestAsk == null || bestAsk.isNull()) {
            throw new IllegalArgumentException("KuCoin: missing buy/sell for " + internalSymbol);
        }

        BigDecimal bid = new BigDecimal(bestBid.asText());
        BigDecimal ask = new BigDecimal(bestAsk.asText());

        if (bid.signum() <= 0 || ask.signum() <= 0) {
            throw new IllegalArgumentException("KuCoin: invalid bid/ask prices for " + internalSymbol);
        }

        BigDecimal bidSize = AdapterJsonUtils.optionalDecimal(data, "bestBidSize");
        BigDecimal askSize = AdapterJsonUtils.optionalDecimal(data, "bestAskSize");

        return new PriceTicker(
                Exchange.KUCOIN,
                internalSymbol,
                market.getNativeSymbol(),
                market.getQuoteAsset(),
                bid,
                ask,
                Instant.now(),
                bidSize,
                askSize,
                AdapterJsonUtils.optionalDecimal(data, "volValue")
        );
    }

    private ExchangeProperties.MarketConfig market(String internalSymbol) {
        ExchangeProperties.ExchangeConfig config = exchangeProperties.getAdapters().get("kucoin");
        if (config == null) {
            return null;
        }
        return config.getMarket(internalSymbol);
    }

    private JsonNode parseJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("KuCoin: failed to parse response JSON", e);
        }
    }

    private PriceTicker parseTicker(JsonNode json, String internalSymbol, ExchangeProperties.MarketConfig market) {
        if (json == null || json.isMissingNode()) {
            throw new IllegalArgumentException("KuCoin response is missing or null");
        }

        // KuCoin's unknown-symbol response is HTTP 200 with {"code":"200000","data":null} —
        // this null check is the actual error boundary, not the HTTP status.
        JsonNode data = json.get("data");
        if (data == null || data.isNull() || data.isMissingNode()) {
            throw new IllegalArgumentException("KuCoin: no data for symbol (unknown or delisted market)");
        }

        JsonNode bestBid = data.get("bestBid");
        JsonNode bestAsk = data.get("bestAsk");
        if (bestBid == null || bestBid.isNull() || bestAsk == null || bestAsk.isNull()) {
            throw new IllegalArgumentException("KuCoin: missing bestBid or bestAsk");
        }

        BigDecimal bid = new BigDecimal(bestBid.asText());
        BigDecimal ask = new BigDecimal(bestAsk.asText());

        if (bid.signum() <= 0 || ask.signum() <= 0) {
            throw new IllegalArgumentException("KuCoin: invalid bid/ask prices");
        }

        BigDecimal bidSize = AdapterJsonUtils.optionalDecimal(data, "bestBidSize");
        BigDecimal askSize = AdapterJsonUtils.optionalDecimal(data, "bestAskSize");

        return new PriceTicker(
                Exchange.KUCOIN,
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
        int levels = depth <= 20 ? 20 : 100;
        String nativeSymbol = market.getNativeSymbol();
        String path = levels == 20
                ? "/api/v1/market/orderbook/level2_20"
                : "/api/v1/market/orderbook/level2_100";

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(path)
                        .queryParam("symbol", nativeSymbol)
                        .build())
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> {
                    log.warn("KuCoin: HTTP {} for depth {}", response.statusCode().value(), nativeSymbol);
                    return Mono.error(new RuntimeException("HTTP " + response.statusCode().value()));
                })
                .bodyToMono(String.class)
                .map(this::parseJson)
                .map(json -> parseDepth(json, internalSymbol, market, levels))
                .onErrorResume(e -> {
                    log.warn("KuCoin: depth error for {}: {}", internalSymbol, e.getMessage());
                    return Mono.empty();
                });
    }

    private OrderBook parseDepth(
            JsonNode json,
            String internalSymbol,
            ExchangeProperties.MarketConfig market,
            int levels
    ) {
        JsonNode data = json.get("data");
        if (data == null || data.isNull() || data.isMissingNode()) {
            throw new IllegalArgumentException("KuCoin: no depth data");
        }

        return new OrderBook(
                Exchange.KUCOIN,
                internalSymbol,
                market.getNativeSymbol(),
                AdapterJsonUtils.parseLevels(data.get("bids"), levels),
                AdapterJsonUtils.parseLevels(data.get("asks"), levels),
                Instant.now()
        );
    }
}
