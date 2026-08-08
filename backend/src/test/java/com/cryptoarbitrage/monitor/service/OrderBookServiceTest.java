package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.dto.RouteOrderBookDto;
import com.cryptoarbitrage.monitor.exchange.Exchange;
import com.cryptoarbitrage.monitor.exchange.ExchangeAdapter;
import com.cryptoarbitrage.monitor.exchange.OrderBook;
import com.cryptoarbitrage.monitor.exchange.OrderBookLevel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderBookServiceTest {

    @Test
    void fetchRouteBooks_returnsBothBooks() {
        ExchangeAdapter binance = mockAdapter(Exchange.BINANCE, orderBook(Exchange.BINANCE, "BTCUSDT"));
        ExchangeAdapter kraken = mockAdapter(Exchange.KRAKEN, orderBook(Exchange.KRAKEN, "XBTUSDT"));
        OrderBookService service = new OrderBookService(List.of(binance, kraken));

        RouteOrderBookDto dto = service.fetchRouteBooks("BTC/USDT", "BINANCE", "KRAKEN", 20).block();

        assertNotNull(dto);
        assertEquals("BTC/USDT", dto.symbol());
        assertEquals("BINANCE", dto.buyBook().exchange());
        assertEquals("KRAKEN", dto.sellBook().exchange());
        assertNull(dto.buyBookError());
        assertNull(dto.sellBookError());
    }

    @Test
    void fetchRouteBooks_emptyAdapter_setsPerLegError() {
        ExchangeAdapter binance = mock(ExchangeAdapter.class);
        when(binance.getExchange()).thenReturn(Exchange.BINANCE);
        when(binance.getOrderBook(anyString(), anyInt())).thenReturn(Mono.empty());

        ExchangeAdapter kraken = mockAdapter(Exchange.KRAKEN, orderBook(Exchange.KRAKEN, "XBTUSDT"));
        OrderBookService service = new OrderBookService(List.of(binance, kraken));

        RouteOrderBookDto dto = service.fetchRouteBooks("BTC/USDT", "binance", "kraken", 10).block();

        assertNotNull(dto);
        assertNull(dto.buyBook());
        assertNotNull(dto.sellBook());
        assertEquals("No order book data from binance", dto.buyBookError());
        assertNull(dto.sellBookError());
    }

    @Test
    void fetchRouteBooks_unknownExchange_errors() {
        OrderBookService service = new OrderBookService(List.of());
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.fetchRouteBooks("BTC/USDT", "NOPE", "BINANCE", 10).block()
        );
        assertEquals("Unknown exchange", error.getMessage());
    }

    @Test
    void fetchRouteBooks_missingAdapter_errors() {
        ExchangeAdapter binance = mockAdapter(Exchange.BINANCE, orderBook(Exchange.BINANCE, "BTCUSDT"));
        OrderBookService service = new OrderBookService(List.of(binance));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.fetchRouteBooks("BTC/USDT", "BINANCE", "KRAKEN", 10).block()
        );
        assertEquals("Exchange adapter not configured", error.getMessage());
    }

    @Test
    void fetchRouteBooks_blankExchange_errors() {
        OrderBookService service = new OrderBookService(List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.fetchRouteBooks("BTC/USDT", " ", "BINANCE", 10).block()
        );
    }

    private static ExchangeAdapter mockAdapter(Exchange exchange, OrderBook book) {
        ExchangeAdapter adapter = mock(ExchangeAdapter.class);
        when(adapter.getExchange()).thenReturn(exchange);
        when(adapter.getOrderBook(anyString(), anyInt())).thenReturn(Mono.just(book));
        return adapter;
    }

    private static OrderBook orderBook(Exchange exchange, String nativeSymbol) {
        return new OrderBook(
                exchange,
                "BTC/USDT",
                nativeSymbol,
                List.of(new OrderBookLevel(new BigDecimal("65000"), new BigDecimal("1"))),
                List.of(new OrderBookLevel(new BigDecimal("65010"), new BigDecimal("1"))),
                Instant.parse("2026-08-08T12:00:00Z")
        );
    }
}
