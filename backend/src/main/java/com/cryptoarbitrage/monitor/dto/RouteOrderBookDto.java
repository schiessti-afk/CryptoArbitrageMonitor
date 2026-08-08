package com.cryptoarbitrage.monitor.dto;

public record RouteOrderBookDto(
        String symbol,
        String buyExchange,
        String sellExchange,
        OrderBookDto buyBook,
        OrderBookDto sellBook,
        String buyBookError,
        String sellBookError
) {}
