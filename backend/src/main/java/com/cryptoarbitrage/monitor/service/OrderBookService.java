package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.dto.OrderBookDto;
import com.cryptoarbitrage.monitor.dto.RouteOrderBookDto;
import com.cryptoarbitrage.monitor.exchange.Exchange;
import com.cryptoarbitrage.monitor.exchange.ExchangeAdapter;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class OrderBookService {

    private final Map<Exchange, ExchangeAdapter> adaptersByExchange;

    public OrderBookService(List<ExchangeAdapter> adapters) {
        Map<Exchange, ExchangeAdapter> map = new EnumMap<>(Exchange.class);
        for (ExchangeAdapter adapter : adapters) {
            map.put(adapter.getExchange(), adapter);
        }
        this.adaptersByExchange = Map.copyOf(map);
    }

    public Mono<RouteOrderBookDto> fetchRouteBooks(
            String symbol,
            String buyExchange,
            String sellExchange,
            int depth
    ) {
        Exchange buy = parseExchange(buyExchange);
        Exchange sell = parseExchange(sellExchange);
        if (buy == null || sell == null) {
            return Mono.error(new IllegalArgumentException("Unknown exchange"));
        }

        ExchangeAdapter buyAdapter = adaptersByExchange.get(buy);
        ExchangeAdapter sellAdapter = adaptersByExchange.get(sell);
        if (buyAdapter == null || sellAdapter == null) {
            return Mono.error(new IllegalArgumentException("Exchange adapter not configured"));
        }

        Mono<BookResult> buyMono = buyAdapter.getOrderBook(symbol, depth)
                .map(book -> new BookResult(OrderBookDto.from(book), null))
                .defaultIfEmpty(new BookResult(null, "No order book data from " + buyExchange));

        Mono<BookResult> sellMono = sellAdapter.getOrderBook(symbol, depth)
                .map(book -> new BookResult(OrderBookDto.from(book), null))
                .defaultIfEmpty(new BookResult(null, "No order book data from " + sellExchange));

        return Mono.zip(buyMono, sellMono)
                .map(tuple -> new RouteOrderBookDto(
                        symbol,
                        buyExchange,
                        sellExchange,
                        tuple.getT1().book(),
                        tuple.getT2().book(),
                        tuple.getT1().error(),
                        tuple.getT2().error()
                ));
    }

    private Exchange parseExchange(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Exchange.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record BookResult(OrderBookDto book, String error) {}
}
