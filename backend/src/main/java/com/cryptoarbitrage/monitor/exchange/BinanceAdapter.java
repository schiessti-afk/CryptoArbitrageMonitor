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
            throw new IllegalArgumentException("Binance: invalid bid/ask prices");
        }

        return new PriceTicker(
                Exchange.BINANCE,
                internalSymbol,
                market.getNativeSymbol(),
                market.getQuoteAsset(),
                bid,
                ask,
                Instant.now()
        );
    }
}
