package com.cryptoarbitrage.monitor.controller;

import com.cryptoarbitrage.monitor.dto.OrderBookDto;
import com.cryptoarbitrage.monitor.dto.OrderBookLevelDto;
import com.cryptoarbitrage.monitor.dto.RouteOrderBookDto;
import com.cryptoarbitrage.monitor.service.OrderBookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderBookController.class)
class OrderBookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderBookService orderBookService;

    @Test
    void getRouteOrderBook_returnsBothBooks() throws Exception {
        Instant now = Instant.parse("2026-08-08T12:00:00Z");
        OrderBookDto buyBook = new OrderBookDto(
                "BINANCE",
                "BTC/USDT",
                "BTCUSDT",
                List.of(),
                List.of(new OrderBookLevelDto(new BigDecimal("65015.28"), new BigDecimal("3.84"))),
                now
        );
        OrderBookDto sellBook = new OrderBookDto(
                "KRAKEN",
                "BTC/USDT",
                "XBTUSDT",
                List.of(new OrderBookLevelDto(new BigDecimal("65020.00"), new BigDecimal("1.50"))),
                List.of(),
                now
        );

        when(orderBookService.fetchRouteBooks(eq("BTC/USDT"), eq("BINANCE"), eq("KRAKEN"), eq(20)))
                .thenReturn(Mono.just(new RouteOrderBookDto(
                        "BTC/USDT", "BINANCE", "KRAKEN", buyBook, sellBook, null, null)));

        mockMvc.perform(get("/api/orderbook/route")
                        .param("symbol", "BTC/USDT")
                        .param("buyExchange", "BINANCE")
                        .param("sellExchange", "KRAKEN")
                        .param("depth", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("BTC/USDT"))
                .andExpect(jsonPath("$.buyBook.exchange").value("BINANCE"))
                .andExpect(jsonPath("$.sellBook.exchange").value("KRAKEN"));
    }

    @Test
    void getRouteOrderBook_missingSymbol_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/orderbook/route")
                        .param("symbol", " ")
                        .param("buyExchange", "BINANCE")
                        .param("sellExchange", "KRAKEN"))
                .andExpect(status().isBadRequest());
    }
}
