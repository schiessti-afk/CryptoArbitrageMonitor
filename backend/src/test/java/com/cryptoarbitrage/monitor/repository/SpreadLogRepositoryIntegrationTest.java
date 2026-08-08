package com.cryptoarbitrage.monitor.repository;

import com.cryptoarbitrage.monitor.model.SpreadLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repository integration against the local Compose Postgres (localhost:5437).
 * Skips cleanly when that database is not reachable — Testcontainers cannot talk to
 * Docker Desktop Engine 29 from this environment.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@EnabledIf("postgresAvailable")
@Transactional
class SpreadLogRepositoryIntegrationTest {

    @Autowired
    private SpreadLogRepository spreadLogRepository;

    static boolean postgresAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5437), 750);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String uniqueSymbol(String prefix) {
        // Keep within spread_log.symbol varchar(20)
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        return prefix + suffix;
    }

    private static SpreadLog newLog(
            String symbol,
            String buyExchange,
            Instant calculatedAt,
            BigDecimal netSpreadPercent
    ) {
        SpreadLog log = new SpreadLog();
        log.setSymbol(symbol);
        log.setBuyExchange(buyExchange);
        log.setSellExchange("KRAKEN");
        log.setBuyPrice(new BigDecimal("100"));
        log.setSellPrice(new BigDecimal("101"));
        log.setRawSpreadPercent(new BigDecimal("1.000"));
        log.setNetSpreadPercent(netSpreadPercent);
        log.setCalculatedAt(calculatedAt);
        return log;
    }

    @Test
    void testSaveAndRetrieveSpreadLog() {
        String symbol = uniqueSymbol("T/");
        SpreadLog saved = spreadLogRepository.save(
                newLog(symbol, "BINANCE", Instant.now(), new BigDecimal("0.054"))
        );
        assertNotNull(saved.getId());

        SpreadLog retrieved = spreadLogRepository.findById(saved.getId()).orElse(null);
        assertNotNull(retrieved);
        assertEquals(symbol, retrieved.getSymbol());
        assertEquals("BINANCE", retrieved.getBuyExchange());
        assertTrue(retrieved.getRawSpreadPercent().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void testFindLatestBySymbol() {
        String symbol = uniqueSymbol("T/");
        Instant now = Instant.now();

        spreadLogRepository.save(newLog(symbol, "BINANCE", now.minusSeconds(60), new BigDecimal("1.36")));
        spreadLogRepository.save(newLog(symbol, "COINBASE", now, new BigDecimal("1.35")));

        List<SpreadLog> latest = spreadLogRepository.findLatestBySymbol(symbol, PageRequest.of(0, 1));

        assertEquals(1, latest.size());
        assertEquals("COINBASE", latest.get(0).getBuyExchange());
    }

    @Test
    void testFindByCalculatedAtBetween() {
        String symbol = uniqueSymbol("T/");
        Instant now = Instant.now();

        SpreadLog oldRow = newLog(symbol, "BINANCE", now.minusSeconds(3600), new BigDecimal("2.11"));
        SpreadLog newRow = newLog(symbol, "COINBASE", now, new BigDecimal("2.12"));
        spreadLogRepository.save(oldRow);
        spreadLogRepository.save(newRow);

        Instant from = now.minusSeconds(1800);
        Instant to = now.plusSeconds(100);
        List<SpreadLog> results = spreadLogRepository.findByCalculatedAtBetween(from, to, PageRequest.of(0, 50));

        assertTrue(results.stream().anyMatch(r ->
                symbol.equals(r.getSymbol()) && "COINBASE".equals(r.getBuyExchange())));
        assertTrue(results.stream().noneMatch(r ->
                symbol.equals(r.getSymbol()) && "BINANCE".equals(r.getBuyExchange())));
    }
}
