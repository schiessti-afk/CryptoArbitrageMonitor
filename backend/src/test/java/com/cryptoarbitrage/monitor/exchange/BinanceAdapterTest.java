package com.cryptoarbitrage.monitor.exchange;

import com.cryptoarbitrage.monitor.config.ExchangeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BinanceAdapterTest {

    private ExchangeProperties exchangeProperties;
    private String batchFixture;

    @BeforeEach
    void setUp() throws IOException {
        exchangeProperties = TestExchangeProperties.singleAdapter(
                "binance",
                "https://api.binance.com",
                Map.of(
                        "BTC_USDT", new String[]{"BTCUSDT", "USDT"},
                        "SOL_USDT", new String[]{"SOLUSDT", "USDT"}
                )
        );
        batchFixture = Files.readString(Paths.get("src/test/resources/fixtures/binance/batch-tickers.json"));
    }

    private BinanceAdapter adapterReturning(String body, HttpStatus status) {
        ExchangeFunction exchangeFunction = request ->
                Mono.just(ClientResponse.create(status)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build());

        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.binance.com")
                .exchangeFunction(exchangeFunction)
                .build();
        return new BinanceAdapter(webClient, exchangeProperties);
    }

    @Test
    void getTickers_batch_returnsAllSupported() {
        BinanceAdapter adapter = adapterReturning(batchFixture, HttpStatus.OK);
        List<PriceTicker> tickers = adapter.getTickers(List.of("BTC/USDT", "SOL/USDT", "BTC/USD"))
                .collectList()
                .block();

        assertNotNull(tickers);
        assertEquals(2, tickers.size());
        assertTrue(tickers.stream().anyMatch(t -> t.symbol().equals("BTC/USDT")));
        assertTrue(tickers.stream().anyMatch(t -> t.symbol().equals("SOL/USDT")));
        assertEquals(0, new BigDecimal("65015.27").compareTo(tickers.stream()
                .filter(t -> t.symbol().equals("BTC/USDT"))
                .findFirst()
                .orElseThrow()
                .bid()));
    }

    @Test
    void getTickers_unsupportedSymbolNeverHitsHttp() {
        BinanceAdapter adapter = adapterReturning(batchFixture, HttpStatus.OK);
        List<PriceTicker> tickers = adapter.getTickers(List.of("BTC/USD"))
                .collectList()
                .block();

        assertNotNull(tickers);
        assertTrue(tickers.isEmpty());
    }
}
