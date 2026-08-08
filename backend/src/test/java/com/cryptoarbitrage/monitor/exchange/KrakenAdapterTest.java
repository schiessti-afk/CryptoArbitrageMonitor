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

class KrakenAdapterTest {

    private ExchangeProperties exchangeProperties;
    private String successFixture;
    private static final String ERROR_FIXTURE = "{\"error\":[\"EAPI:Invalid key\"],\"result\":{}}";

    @BeforeEach
    void setUp() throws IOException {
        exchangeProperties = TestExchangeProperties.singleAdapter(
                "kraken",
                "https://api.kraken.com",
                Map.of(
                        "BTC_USD", new String[]{"XXBTZUSD", "USD"},
                        "ETH_USD", new String[]{"XETHZUSD", "USD"}
                )
        );

        successFixture = new String(Files.readAllBytes(
                Paths.get("src/test/resources/fixtures/kraken/xxbtzusd.json")
        ));
    }

    private KrakenAdapter adapterReturning(String body, HttpStatus status) {
        ExchangeFunction exchangeFunction = request ->
                Mono.just(ClientResponse.create(status)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build());

        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.kraken.com")
                .exchangeFunction(exchangeFunction)
                .build();
        return new KrakenAdapter(webClient, exchangeProperties);
    }

    @Test
    void testGetTicker_Successful() {
        KrakenAdapter adapter = adapterReturning(successFixture, HttpStatus.OK);
        PriceTicker ticker = adapter.getTicker("BTC/USD").block();

        assertNotNull(ticker);
        assertEquals(Exchange.KRAKEN, ticker.exchange());
        assertEquals("BTC/USD", ticker.symbol());
        assertEquals("XXBTZUSD", ticker.nativeSymbol());
        assertEquals("USD", ticker.quoteAsset());
        assertTrue(ticker.bid().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(ticker.ask().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void testGetTicker_ErrorArrayInside200_ReturnsEmpty() {
        // Kraken's error shape: HTTP 200 with a non-empty "error" array — must not throw
        // uncaught, must resolve to empty so the poll cycle continues with other exchanges.
        KrakenAdapter adapter = adapterReturning(ERROR_FIXTURE, HttpStatus.OK);
        PriceTicker ticker = adapter.getTicker("BTC/USD").block();

        assertNull(ticker);
    }

    @Test
    void testSupports_KnownAndUnknownSymbol() {
        KrakenAdapter adapter = adapterReturning(successFixture, HttpStatus.OK);

        assertTrue(adapter.supports("BTC/USD"));
        assertTrue(adapter.supports("ETH/USD"));
        assertFalse(adapter.supports("BTC/USDT"), "Kraken config in this test has no BTC/USDT market");
    }

    @Test
    void testGetTicker_UnsupportedSymbol_ReturnsEmptyWithoutRequest() {
        KrakenAdapter adapter = adapterReturning(successFixture, HttpStatus.OK);

        PriceTicker ticker = adapter.getTicker("BTC/USDT").block();

        assertNull(ticker);
    }

    @Test
    void testExchangeEnum() {
        KrakenAdapter adapter = adapterReturning(successFixture, HttpStatus.OK);
        assertEquals("KRAKEN", adapter.getExchange().name());
    }
}
