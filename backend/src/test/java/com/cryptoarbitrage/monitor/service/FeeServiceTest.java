package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.config.ExchangeProperties;
import com.cryptoarbitrage.monitor.exchange.Exchange;
import com.cryptoarbitrage.monitor.model.ExchangeFee;
import com.cryptoarbitrage.monitor.repository.ExchangeFeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeeServiceTest {

    private ExchangeFeeRepository feeRepository;
    private FeeService feeService;

    @BeforeEach
    void setUp() {
        feeRepository = mock(ExchangeFeeRepository.class);
        feeService = new FeeService(feeRepository, new ExchangeProperties());
    }

    @Test
    void getFeeForExchange_returnsDbValue() {
        ExchangeFee fee = new ExchangeFee();
        fee.setExchange("BINANCE");
        fee.setTakerFee(new BigDecimal("0.0008"));
        when(feeRepository.findByExchange("BINANCE")).thenReturn(Optional.of(fee));

        assertEquals(0, new BigDecimal("0.0008").compareTo(feeService.getFeeForExchange(Exchange.BINANCE)));
    }

    @Test
    void getFeeForExchange_missingRow_usesDefault() {
        when(feeRepository.findByExchange("KRAKEN")).thenReturn(Optional.empty());

        assertEquals(0, new BigDecimal("0.001").compareTo(feeService.getFeeForExchange(Exchange.KRAKEN)));
    }

    @Test
    void getFeeForExchange_repositoryException_usesDefault() {
        when(feeRepository.findByExchange("COINBASE")).thenThrow(new RuntimeException("db down"));

        assertEquals(0, new BigDecimal("0.001").compareTo(feeService.getFeeForExchange(Exchange.COINBASE)));
    }

    @Test
    void getAllFees_includesEveryExchange() {
        when(feeRepository.findByExchange(anyString())).thenReturn(Optional.empty());

        Map<Exchange, BigDecimal> fees = feeService.getAllFees();

        assertEquals(Exchange.values().length, fees.size());
        for (Exchange exchange : Exchange.values()) {
            assertTrue(fees.containsKey(exchange));
            assertEquals(0, new BigDecimal("0.001").compareTo(fees.get(exchange)));
        }
    }
}
