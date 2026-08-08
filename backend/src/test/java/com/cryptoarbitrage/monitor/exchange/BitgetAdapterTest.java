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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BitgetAdapterTest {

    private ExchangeProperties exchangeProperties;
    private String successFixture;
    private String errorFixture;

    @BeforeEach
    void setUp() throws IOException {
        // Bitget is USDT-only for BTC/ETH — no BTC_USD entry, matching production config.
        exchangeProperties = TestExchangeProperties.singleAdapter(
                "bitget",
                "https://api.bitget.com",
                Map.of("BTC_USDT", new String[]{"BTCUSDT", "USDT"})
        );

        successFixture = new String(Files.readAllBytes(
                Paths.get("src/test/resources/fixtures/bitget/btcusdt.json")
        ));
        errorFixture = new String(Files.readAllBytes(
                Paths.get("src/test/resources/fixtures/bitget/error-unknown-symbol.json")
        ));
    }

    private BitgetAdapter adapterReturning(String body, HttpStatus status) {
        ExchangeFunction exchangeFunction = request ->
                Mono.just(ClientResponse.create(status)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build());

        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.bitget.com")
                .exchangeFunction(exchangeFunction)
                .build();
        return new BitgetAdapter(webClient, exchangeProperties);
    }

    @Test
    void testGetTicker_Successful() {
        BitgetAdapter adapter = adapterReturning(successFixture, HttpStatus.OK);
        PriceTicker ticker = adapter.getTicker("BTC/USDT").block();

        assertNotNull(ticker);
        assertEquals(Exchange.BITGET, ticker.exchange());
        assertEquals("BTC/USDT", ticker.symbol());
        assertEquals("BTCUSDT", ticker.nativeSymbol());
        assertEquals("USDT", ticker.quoteAsset());
        assertEquals(new BigDecimal("65014.21"), ticker.bid());
        assertEquals(new BigDecimal("65014.22"), ticker.ask());
        assertEquals(new BigDecimal("5.142685"), ticker.bidSize());
        assertEquals(new BigDecimal("0.420206"), ticker.askSize());
        assertEquals(new BigDecimal("63217571.218056"), ticker.quoteVolume24h());
    }

    @Test
    void getOrderBook_parsesDepth() throws IOException {
        String depthFixture = Files.readString(Paths.get("src/test/resources/fixtures/bitget/depth-btcusdt.json"));
        BitgetAdapter adapter = adapterReturning(depthFixture, HttpStatus.OK);
        var book = adapter.getOrderBook("BTC/USDT", 20).block();

        assertNotNull(book);
        assertEquals(2, book.asks().size());
        assertEquals(new BigDecimal("65014.22"), book.asks().get(0).price());
    }

    @Test
    void testGetTicker_ErrorCodeWithHttp400_ReturnsEmpty() {
        // Verified live: Bitget returns HTTP 400 AND code "40034" for an unknown symbol
        // (e.g. BTCUSD, which it does not list). Either signal alone should be enough to fail.
        BitgetAdapter adapter = adapterReturning(errorFixture, HttpStatus.BAD_REQUEST);
        PriceTicker ticker = adapter.getTicker("BTC/USDT").block();

        assertNull(ticker);
    }

    @Test
    void testGetTicker_ErrorCodeWithHttp200_StillFails() {
        // Even if a future Bitget error path returns 200 with a non-success code, the code
        // check inside parseTicker must catch it independent of HTTP status.
        BitgetAdapter adapter = adapterReturning(errorFixture, HttpStatus.OK);
        PriceTicker ticker = adapter.getTicker("BTC/USDT").block();

        assertNull(ticker);
    }

    @Test
    void testSupports_NoUsdMarket() {
        BitgetAdapter adapter = adapterReturning(successFixture, HttpStatus.OK);

        assertTrue(adapter.supports("BTC/USDT"));
        assertFalse(adapter.supports("BTC/USD"), "Bitget does not list a BTC/USD market");
    }

    @Test
    void testGetTicker_UnsupportedSymbol_ReturnsEmptyWithoutRequest() {
        BitgetAdapter adapter = adapterReturning(successFixture, HttpStatus.OK);

        PriceTicker ticker = adapter.getTicker("BTC/USD").block();

        assertNull(ticker);
    }

    @Test
    void testExchangeEnum() {
        BitgetAdapter adapter = adapterReturning(successFixture, HttpStatus.OK);
        assertEquals("BITGET", adapter.getExchange().name());
    }
}
