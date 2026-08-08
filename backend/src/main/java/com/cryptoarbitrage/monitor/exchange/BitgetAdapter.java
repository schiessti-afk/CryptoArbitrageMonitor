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
 * Bitget spot ticker. USDT-only for BTC/ETH — no BTC/USD or ETH/USD market exists
 * (verified live: {@code symbol=BTCUSD} returns HTTP 400, code "40034",
 * "Parameter BTCUSD does not exist"). {@link #supports(String)} reflects that via config
 * presence, so this adapter is never polled for a USD market.
 */
@Component
public class BitgetAdapter implements ExchangeAdapter {

    private static final Logger log = LoggerFactory.getLogger(BitgetAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUCCESS_CODE = "00000";

    private final WebClient webClient;
    private final ExchangeProperties exchangeProperties;

    public BitgetAdapter(
            @Qualifier("bitgetWebClient") WebClient webClient,
            ExchangeProperties exchangeProperties
    ) {
        this.webClient = webClient;
        this.exchangeProperties = exchangeProperties;
    }

    @Override
    public Exchange getExchange() {
        return Exchange.BITGET;
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
                .uri("/api/v2/spot/market/tickers?symbol={symbol}", nativeSymbol)
                .retrieve()
                // Bitget returns HTTP 400 for an unknown symbol as well as its own error code
                // in the body; either signal is a failure.
                .onStatus(status -> !status.is2xxSuccessful(), response -> {
                    log.warn("Bitget: HTTP {} for symbol {}", response.statusCode().value(), nativeSymbol);
                    return Mono.error(new RuntimeException("HTTP " + response.statusCode().value()));
                })
                .bodyToMono(String.class)
                .map(this::parseJson)
                .map(json -> parseTicker(json, internalSymbol, market))
                .onErrorResume(e -> {
                    log.warn("Bitget: error fetching {}: {}", internalSymbol, e.getMessage());
                    return Mono.empty();
                });
    }

    private ExchangeProperties.MarketConfig market(String internalSymbol) {
        ExchangeProperties.ExchangeConfig config = exchangeProperties.getAdapters().get("bitget");
        if (config == null) {
            return null;
        }
        return config.getMarket(internalSymbol);
    }

    private JsonNode parseJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Bitget: failed to parse response JSON", e);
        }
    }

    private PriceTicker parseTicker(JsonNode json, String internalSymbol, ExchangeProperties.MarketConfig market) {
        // Bitget returns { "code": "00000", "msg": "success", "data": [ {...} ] } on success,
        // or { "code": "40034", "msg": "...", "data": null } on a bad symbol (also HTTP 400,
        // but the code check here is the authoritative one — some error paths return 200 with
        // a non-success code).
        if (json == null || json.isMissingNode()) {
            throw new IllegalArgumentException("Bitget response is missing or null");
        }

        JsonNode codeNode = json.get("code");
        if (codeNode == null || !SUCCESS_CODE.equals(codeNode.asText())) {
            String msg = json.has("msg") ? json.get("msg").asText() : "unknown error";
            throw new IllegalArgumentException("Bitget error: " + msg);
        }

        JsonNode data = json.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            throw new IllegalArgumentException("Bitget: missing or empty data array");
        }

        JsonNode ticker = data.get(0);
        BigDecimal bid = new BigDecimal(ticker.get("bidPr").asText());
        BigDecimal ask = new BigDecimal(ticker.get("askPr").asText());

        if (bid.signum() <= 0 || ask.signum() <= 0) {
            throw new IllegalArgumentException("Bitget: invalid bid/ask prices");
        }

        return new PriceTicker(
                Exchange.BITGET,
                internalSymbol,
                market.getNativeSymbol(),
                market.getQuoteAsset(),
                bid,
                ask,
                Instant.now()
        );
    }
}
