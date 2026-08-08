package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.config.ExchangeProperties;
import com.cryptoarbitrage.monitor.exchange.Exchange;
import com.cryptoarbitrage.monitor.model.ExchangeFee;
import com.cryptoarbitrage.monitor.repository.ExchangeFeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Manages exchange taker fees from the database.
 * Caches fees and provides fallback to configuration if not found.
 */
@Service
public class FeeService {

    private static final Logger log = LoggerFactory.getLogger(FeeService.class);

    private final ExchangeFeeRepository feeRepository;
    private final ExchangeProperties exchangeProperties;

    public FeeService(ExchangeFeeRepository feeRepository, ExchangeProperties exchangeProperties) {
        this.feeRepository = feeRepository;
        this.exchangeProperties = exchangeProperties;
    }

    /**
     * Get all current taker fees (one per exchange).
     * Fetches from DB, falls back to properties if not found.
     */
    public Map<Exchange, BigDecimal> getAllFees() {
        Map<Exchange, BigDecimal> fees = new HashMap<>();

        for (Exchange exchange : Exchange.values()) {
            fees.put(exchange, getFeeForExchange(exchange));
        }

        return fees;
    }

    /**
     * Get the taker fee for a single exchange.
     * Tries DB first, falls back to configured default.
     */
    public BigDecimal getFeeForExchange(Exchange exchange) {
        try {
            Optional<ExchangeFee> dbFee = feeRepository.findByExchange(exchange.name());
            if (dbFee.isPresent()) {
                return dbFee.get().getTakerFee();
            }
        } catch (Exception e) {
            log.warn("Failed to fetch fee for {} from DB: {}", exchange, e.getMessage());
        }

        // Fallback to configured default
        ExchangeProperties.ExchangeConfig config = exchangeProperties.getAdapters().get(
                exchange.name().toLowerCase()
        );
        if (config != null) {
            // For now, there's no fee in the config — it's only in the DB.
            // But this structure allows adding it to properties later if needed.
        }

        log.warn("No fee found for {}, using 0.001 (0.1%) default", exchange);
        return new BigDecimal("0.001");
    }
}
