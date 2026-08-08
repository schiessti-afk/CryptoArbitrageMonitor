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

class KuCoinAdapterTest {

    private ExchangeProperties exchangeProperties;
    private String successFixture;
    private String nullDataFixture;

    @BeforeEach
    void setUp() throws IOException {
        // KuCoin is USDT-only for BTC/ETH — no BTC_USD entry, matching production config.
        exchangeProperties = TestExchangeProperties.singleAdapter(
                "kucoin",
                "https://api.kucoin.com",
                Map.of("BTC_USDT", new String[]{"BTC-USDT", "USDT"})
        );

        successFixture = new String(Files.readAllBytes(
                Paths.get("src/test/resources/fixtures/kucoin/btc-usdt.json")
        ));
        nullDataFixture = new String(Files.readAllBytes(
                Paths.get("src/test/resources/fixtures/kucoin/error-null-data.json")
        ));
    }

    private KuCoinAdapter adapterReturning(String body, HttpStatus status) {
        ExchangeFunction exchangeFunction = request ->
                Mono.just(ClientResponse.create(status)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .build());

        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.kucoin.com")
                .exchangeFunction(exchangeFunction)
                .build();
        return new KuCoinAdapter(webClient, exchangeProperties);
    }

    @Test
    void testGetTicker_Successful() {
        KuCoinAdapter adapter = adapterReturning(successFixture, HttpStatus.OK);
        PriceTicker ticker = adapter.getTicker("BTC/USDT").block();

        assertNotNull(ticker);
        assertEquals(Exchange.KUCOIN, ticker.exchange());
        assertEquals("BTC/USDT", ticker.symbol());
        assertEquals("BTC-USDT", ticker.nativeSymbol());
        assertEquals("USDT", ticker.quoteAsset());
        assertEquals(new BigDecimal("65011.3"), ticker.bid());
        assertEquals(new BigDecimal("65011.4"), ticker.ask());
    }

    /**
     * The critical case: KuCoin's unknown-symbol response is HTTP 200 with
     * {"code":"200000","data":null} — verified live. The standard onStatus non-2xx check cannot
     * catch this. Without an explicit null-data guard, {@code data.get("bestBid")} on a NullNode
     * throws an unguarded NPE instead of resolving to a clean adapter failure.
     */
    @Test
    void testGetTicker_NullDataOnHttp200_ReturnsEmptyNotNpe() {
        KuCoinAdapter adapter = adapterReturning(nullDataFixture, HttpStatus.OK);

        PriceTicker ticker = assertDoesNotThrow(() -> adapter.getTicker("BTC/USDT").block());
        assertNull(ticker);
    }

    @Test
    void testSupports_NoUsdMarket() {
        KuCoinAdapter adapter = adapterReturning(successFixture, HttpStatus.OK);

        assertTrue(adapter.supports("BTC/USDT"));
        assertFalse(adapter.supports("BTC/USD"), "KuCoin does not list a BTC/USD market");
    }

    @Test
    void testGetTicker_UnsupportedSymbol_ReturnsEmptyWithoutRequest() {
        KuCoinAdapter adapter = adapterReturning(successFixture, HttpStatus.OK);

        PriceTicker ticker = adapter.getTicker("BTC/USD").block();

        assertNull(ticker);
    }

    @Test
    void testExchangeEnum() {
        KuCoinAdapter adapter = adapterReturning(successFixture, HttpStatus.OK);
        assertEquals("KUCOIN", adapter.getExchange().name());
    }
}
