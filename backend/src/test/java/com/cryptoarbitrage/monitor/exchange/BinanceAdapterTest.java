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
                        "SOL_USDT", new String[]{"SOLUSDT", "USDT"},
                        "ADA_USDT", new String[]{"ADAUSDT", "USDT"},
                        "SHIB_USDT", new String[]{"SHIBUSDT", "USDT"}
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
        List<PriceTicker> tickers = adapter.getTickers(List.of("BTC/USDT", "SOL/USDT", "ADA/USDT", "SHIB/USDT", "BTC/USD"))
                .collectList()
                .block();

        assertNotNull(tickers);
        assertEquals(4, tickers.size());
        assertTrue(tickers.stream().anyMatch(t -> t.symbol().equals("BTC/USDT")));
        assertTrue(tickers.stream().anyMatch(t -> t.symbol().equals("SOL/USDT")));
        assertTrue(tickers.stream().anyMatch(t -> t.symbol().equals("ADA/USDT")));
        assertTrue(tickers.stream().anyMatch(t -> t.symbol().equals("SHIB/USDT")));
        assertEquals(0, new BigDecimal("65015.27").compareTo(tickers.stream()
                .filter(t -> t.symbol().equals("BTC/USDT"))
                .findFirst()
                .orElseThrow()
                .bid()));

        PriceTicker btc = tickers.stream().filter(t -> t.symbol().equals("BTC/USDT")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("5.66862000"), btc.bidSize());
        assertEquals(new BigDecimal("3.84780000"), btc.askSize());
        assertNull(btc.quoteVolume24h());
    }

    @Test
    void getOrderBook_parsesDepth() throws IOException {
        String depthFixture = Files.readString(Paths.get("src/test/resources/fixtures/binance/depth-btcusd.json"));
        exchangeProperties = TestExchangeProperties.singleAdapter(
                "binance",
                "https://api.binance.com",
                Map.of("BTC_USD", new String[]{"BTCUSD", "USD"})
        );
        BinanceAdapter adapter = adapterReturning(depthFixture, HttpStatus.OK);
        var book = adapter.getOrderBook("BTC/USD", 20).block();

        assertNotNull(book);
        assertEquals(2, book.asks().size());
        assertEquals(new BigDecimal("64943.19"), book.asks().get(0).price());
        assertEquals(new BigDecimal("0.00147000"), book.asks().get(0).size());
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

    @Test
    void getTickers_zeroBidAskSkipsSymbolWithoutKillingBatch() {
        exchangeProperties = TestExchangeProperties.singleAdapter(
                "binance",
                "https://api.binance.com",
                Map.of(
                        "BTC_USDT", new String[]{"BTCUSDT", "USDT"},
                        "TON_USDT", new String[]{"TONUSDT", "USDT"},
                        "SOL_USDT", new String[]{"SOLUSDT", "USDT"}
                )
        );
        String body = """
                [
                  {"symbol":"BTCUSDT","bidPrice":"65015.27000000","bidQty":"5.66862000","askPrice":"65015.28000000","askQty":"3.84780000"},
                  {"symbol":"TONUSDT","bidPrice":"0.00000000","bidQty":"0.00000000","askPrice":"0.00000000","askQty":"0.00000000"},
                  {"symbol":"SOLUSDT","bidPrice":"75.56000000","bidQty":"504.46800000","askPrice":"75.57000000","askQty":"291.33400000"}
                ]
                """;
        BinanceAdapter adapter = adapterReturning(body, HttpStatus.OK);

        List<PriceTicker> tickers = adapter.getTickers(List.of("BTC/USDT", "TON/USDT", "SOL/USDT"))
                .collectList()
                .block();

        assertNotNull(tickers);
        assertEquals(2, tickers.size());
        assertTrue(tickers.stream().anyMatch(t -> t.symbol().equals("BTC/USDT")));
        assertTrue(tickers.stream().anyMatch(t -> t.symbol().equals("SOL/USDT")));
        assertTrue(tickers.stream().noneMatch(t -> t.symbol().equals("TON/USDT")));
    }
}
