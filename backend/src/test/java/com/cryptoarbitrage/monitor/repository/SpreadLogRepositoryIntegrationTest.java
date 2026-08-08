package com.cryptoarbitrage.monitor.repository;

import com.cryptoarbitrage.monitor.model.SpreadLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class SpreadLogRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("arbitrage")
            .withUsername("arbitrage")
            .withPassword("arbitrage");

    @Autowired
    private SpreadLogRepository spreadLogRepository;

    @Test
    void testSaveAndRetrieveSpreadLog() {
        // Create a test spread log
        SpreadLog log = new SpreadLog();
        log.setSymbol("BTC/USD");
        log.setBuyExchange("BINANCE");
        log.setSellExchange("KRAKEN");
        log.setBuyPrice(new BigDecimal("64900.00000000"));
        log.setSellPrice(new BigDecimal("65000.00000000"));
        log.setRawSpreadPercent(new BigDecimal("0.154"));
        log.setNetSpreadPercent(new BigDecimal("0.054"));
        log.setCalculatedAt(Instant.now());

        // Save
        SpreadLog saved = spreadLogRepository.save(log);
        assertNotNull(saved.getId());

        // Retrieve
        SpreadLog retrieved = spreadLogRepository.findById(saved.getId()).orElse(null);
        assertNotNull(retrieved);
        assertEquals("BTC/USD", retrieved.getSymbol());
        assertEquals("BINANCE", retrieved.getBuyExchange());
        assertEquals("KRAKEN", retrieved.getSellExchange());
        assertTrue(retrieved.getRawSpreadPercent().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void testFindLatestBySymbol() {
        Instant now = Instant.now();

        // Insert two logs for the same symbol with different timestamps
        SpreadLog log1 = new SpreadLog();
        log1.setSymbol("BTC/USD");
        log1.setBuyExchange("BINANCE");
        log1.setSellExchange("KRAKEN");
        log1.setBuyPrice(new BigDecimal("64000"));
        log1.setSellPrice(new BigDecimal("65000"));
        log1.setRawSpreadPercent(new BigDecimal("1.56"));
        log1.setNetSpreadPercent(new BigDecimal("1.36"));
        log1.setCalculatedAt(now.minusSeconds(60));
        spreadLogRepository.save(log1);

        SpreadLog log2 = new SpreadLog();
        log2.setSymbol("BTC/USD");
        log2.setBuyExchange("COINBASE");
        log2.setSellExchange("BINANCE");
        log2.setBuyPrice(new BigDecimal("64500"));
        log2.setSellPrice(new BigDecimal("65500"));
        log2.setRawSpreadPercent(new BigDecimal("1.55"));
        log2.setNetSpreadPercent(new BigDecimal("1.35"));
        log2.setCalculatedAt(now);
        spreadLogRepository.save(log2);

        // Fetch latest
        List<SpreadLog> latest = spreadLogRepository.findLatestBySymbol("BTC/USD", PageRequest.of(0, 1));

        assertEquals(1, latest.size());
        assertEquals("COINBASE", latest.get(0).getBuyExchange());  // Most recent
    }

    @Test
    void testFindByCalculatedAtBetween() {
        Instant now = Instant.now();

        SpreadLog log1 = new SpreadLog();
        log1.setSymbol("ETH/USD");
        log1.setBuyExchange("BINANCE");
        log1.setSellExchange("KRAKEN");
        log1.setBuyPrice(new BigDecimal("1900"));
        log1.setSellPrice(new BigDecimal("1950"));
        log1.setRawSpreadPercent(new BigDecimal("2.63"));
        log1.setNetSpreadPercent(new BigDecimal("2.11"));
        log1.setCalculatedAt(now.minusSeconds(3600));
        spreadLogRepository.save(log1);

        SpreadLog log2 = new SpreadLog();
        log2.setSymbol("ETH/USD");
        log2.setBuyExchange("COINBASE");
        log2.setSellExchange("BINANCE");
        log2.setBuyPrice(new BigDecimal("1920"));
        log2.setSellPrice(new BigDecimal("1980"));
        log2.setRawSpreadPercent(new BigDecimal("3.12"));
        log2.setNetSpreadPercent(new BigDecimal("2.12"));
        log2.setCalculatedAt(now);
        spreadLogRepository.save(log2);

        // Query with time range
        Instant from = now.minusSeconds(1800);
        Instant to = now.plusSeconds(100);
        List<SpreadLog> results = spreadLogRepository.findByCalculatedAtBetween(from, to, PageRequest.of(0, 10));

        assertEquals(1, results.size());  // Only log2 falls within the range
        assertEquals("COINBASE", results.get(0).getBuyExchange());
    }
}
