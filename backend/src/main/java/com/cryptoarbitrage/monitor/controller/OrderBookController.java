package com.cryptoarbitrage.monitor.controller;

import com.cryptoarbitrage.monitor.dto.RouteOrderBookDto;
import com.cryptoarbitrage.monitor.service.OrderBookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/orderbook")
@CrossOrigin(origins = "*")
public class OrderBookController {

    private final OrderBookService orderBookService;

    public OrderBookController(OrderBookService orderBookService) {
        this.orderBookService = orderBookService;
    }

    /**
     * GET /api/orderbook/route?symbol=BTC/USDT&buyExchange=BINANCE&sellExchange=KRAKEN&depth=20
     */
    @GetMapping("/route")
    public Mono<ResponseEntity<?>> getRouteOrderBook(
            @RequestParam String symbol,
            @RequestParam String buyExchange,
            @RequestParam String sellExchange,
            @RequestParam(defaultValue = "20") int depth
    ) {
        if (symbol == null || symbol.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "symbol is required")));
        }
        if (depth < 1 || depth > 100) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "depth must be between 1 and 100")));
        }

        return orderBookService.fetchRouteBooks(symbol, buyExchange, sellExchange, depth)
                .<ResponseEntity<?>>map(dto -> {
                    if (dto.buyBook() == null && dto.sellBook() == null) {
                        return ResponseEntity.status(502).body(dto);
                    }
                    return ResponseEntity.ok(dto);
                })
                .onErrorResume(IllegalArgumentException.class, e ->
                        Mono.just(ResponseEntity.badRequest().body(Map.of("error", e.getMessage()))));
    }
}
