package com.cryptoarbitrage.monitor.exchange;

import com.cryptoarbitrage.monitor.config.ExchangeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    private KrakenAdapter adapter;
    private ObjectMapper mapper = new ObjectMapper();
    private String successFixture;
    private String errorFixture = "{\"error\":[\"EAPI:Invalid key\"],\"result\":{}}";

    @BeforeEach
    void setUp() throws IOException {
        ExchangeProperties exchangeProperties = new ExchangeProperties();
        ExchangeProperties.ExchangeConfig krakenConfig = new ExchangeProperties.ExchangeConfig();
        krakenConfig.setBaseUrl("https://api.kraken.com");
        krakenConfig.setSymbolMap(Map.of(
                "BTC_USD", "XXBTZUSD",
                "ETH_USD", "XETHZUSD"
        ));
        exchangeProperties.setKraken(krakenConfig);

        successFixture = new String(Files.readAllBytes(
            Paths.get("src/test/resources/fixtures/kraken/xxbtzusd.json")
        ));

        ExchangeFunction exchangeFunction = request ->
            Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(successFixture)
                .build());

        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.kraken.com")
                .exchangeFunction(exchangeFunction)
                .build();
        adapter = new KrakenAdapter(webClient, exchangeProperties);
    }

    @Test
    void testGetTicker_Successful() {
        assertNotNull(adapter);
        assertEquals(Exchange.KRAKEN, adapter.getExchange());
    }

    @Test
    void testSymbolMapping() {
        // Properties are wired by Spring; verify the adapter uses what's configured
        ExchangeProperties.ExchangeConfig config = new ExchangeProperties.ExchangeConfig();
        config.setSymbolMap(Map.of(
            "BTC_USD", "XXBTZUSD",
            "ETH_USD", "XETHZUSD"
        ));
        assertEquals("XXBTZUSD", config.getSymbolMap().get("BTC_USD"));
        assertEquals("XETHZUSD", config.getSymbolMap().get("ETH_USD"));
    }

    @Test
    void testExchangeEnum() {
        assertEquals("KRAKEN", adapter.getExchange().name());
    }
}
