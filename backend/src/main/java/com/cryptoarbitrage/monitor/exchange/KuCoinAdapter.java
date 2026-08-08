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

        return new PriceTicker(
                Exchange.KUCOIN,
                internalSymbol,
                market.getNativeSymbol(),
                market.getQuoteAsset(),
                bid,
                ask,
                Instant.now()
        );
    }
}
