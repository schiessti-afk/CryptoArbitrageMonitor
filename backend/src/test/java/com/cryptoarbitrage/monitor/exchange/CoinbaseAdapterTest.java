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

class CoinbaseAdapterTest {

    private ExchangeProperties exchangeProperties;
    private String tickerFixture;
    private String depthFixture;

    @BeforeEach
    void setUp() throws IOException {
        exchangeProperties = TestExchangeProperties.singleAdapter(
                "coinbase",
                "https://api.exchange.coinbase.com",
                Map.of("BTC_USD", new String[]{"BTC-USD", "USD"})
        );
        tickerFixture = Files.readString(Paths.get("src/test/resources/fixtures/coinbase/btcusd.json"));
        depthFixture = Files.readString(Paths.get("src/test/resources/fixtures/coinbase/depth-btcusd.json"));
    }

    private CoinbaseAdapter adapterReturning(String body, HttpStatus status) {
        ExchangeFunction exchangeFunction = request ->
                Mono.just(ClientResponse.create(status)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build());

        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.exchange.coinbase.com")
                .exchangeFunction(exchangeFunction)
                .build();
        return new CoinbaseAdapter(webClient, exchangeProperties);
    }

    @Test
    void getExchange_returnsCoinbase() {
        assertEquals(Exchange.COINBASE, adapterReturning(tickerFixture, HttpStatus.OK).getExchange());
    }

    @Test
    void supports_knownAndUnknownMarkets() {
        CoinbaseAdapter adapter = adapterReturning(tickerFixture, HttpStatus.OK);
        assertTrue(adapter.supports("BTC/USD"));
        assertFalse(adapter.supports("BTC/USDT"));
    }

    @Test
    void getTicker_parsesBidAskAndQuoteVolume() {
        CoinbaseAdapter adapter = adapterReturning(tickerFixture, HttpStatus.OK);
        PriceTicker ticker = adapter.getTicker("BTC/USD").block();

        assertNotNull(ticker);
        assertEquals(Exchange.COINBASE, ticker.exchange());
        assertEquals("BTC/USD", ticker.symbol());
        assertEquals("BTC-USD", ticker.nativeSymbol());
        assertEquals(0, new BigDecimal("64928.46").compareTo(ticker.bid()));
        assertEquals(0, new BigDecimal("64928.47").compareTo(ticker.ask()));
        assertNotNull(ticker.quoteVolume24h());
        assertEquals(0, new BigDecimal("5856.93400554").multiply(new BigDecimal("64928.47"))
                .compareTo(ticker.quoteVolume24h()));
    }

    @Test
    void getTicker_unsupportedSymbol_returnsEmpty() {
        CoinbaseAdapter adapter = adapterReturning(tickerFixture, HttpStatus.OK);
        assertNull(adapter.getTicker("ETH/USDT").block());
    }

    @Test
    void getTicker_httpError_returnsEmpty() {
        CoinbaseAdapter adapter = adapterReturning("{}", HttpStatus.BAD_REQUEST);
        assertNull(adapter.getTicker("BTC/USD").block());
    }

    @Test
    void getOrderBook_parsesDepthLevels() {
        CoinbaseAdapter adapter = adapterReturning(depthFixture, HttpStatus.OK);
        OrderBook book = adapter.getOrderBook("BTC/USD", 20).block();

        assertNotNull(book);
        assertEquals(2, book.bids().size());
        assertEquals(2, book.asks().size());
        assertEquals(new BigDecimal("64928.46"), book.bids().get(0).price());
        assertEquals(new BigDecimal("0.5"), book.bids().get(0).size());
        assertEquals(new BigDecimal("64928.47"), book.asks().get(0).price());
    }

    @Test
    void getOrderBook_clampsDepthToFifty() {
        CoinbaseAdapter adapter = adapterReturning(depthFixture, HttpStatus.OK);
        OrderBook book = adapter.getOrderBook("BTC/USD", 200).block();

        assertNotNull(book);
        assertTrue(book.bids().size() <= 50);
        assertTrue(book.asks().size() <= 50);
    }

    @Test
    void getOrderBook_unsupportedSymbol_returnsEmpty() {
        CoinbaseAdapter adapter = adapterReturning(depthFixture, HttpStatus.OK);
        assertNull(adapter.getOrderBook("SOL/USDT", 10).block());
    }

    @Test
    void getTickers_fansOutSupportedSymbols() {
        CoinbaseAdapter adapter = adapterReturning(tickerFixture, HttpStatus.OK);
        List<PriceTicker> tickers = adapter.getTickers(List.of("BTC/USD", "ETH/USDT"))
                .collectList()
                .block();

        assertNotNull(tickers);
        assertEquals(1, tickers.size());
        assertEquals("BTC/USD", tickers.get(0).symbol());
    }
}
