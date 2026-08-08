package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.dto.DatabaseFlushResultDto;
import com.cryptoarbitrage.monitor.dto.DatabaseStatsDto;
import com.cryptoarbitrage.monitor.repository.SpreadLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseAdminService {

    private final JdbcTemplate jdbcTemplate;
    private final SpreadLogRepository spreadLogRepository;

    public DatabaseAdminService(JdbcTemplate jdbcTemplate, SpreadLogRepository spreadLogRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.spreadLogRepository = spreadLogRepository;
    }

    public DatabaseStatsDto getStats() {
        Long sizeBytes = jdbcTemplate.queryForObject(
                "SELECT pg_database_size(current_database())", Long.class);
        String sizePretty = jdbcTemplate.queryForObject(
                "SELECT pg_size_pretty(pg_database_size(current_database()))", String.class);
        Long spreadLogBytes = jdbcTemplate.queryForObject(
                "SELECT pg_total_relation_size('spread_log')", Long.class);
        String spreadLogSizePretty = jdbcTemplate.queryForObject(
                "SELECT pg_size_pretty(pg_total_relation_size('spread_log'))", String.class);
        long spreadLogRows = spreadLogRepository.count();

        return new DatabaseStatsDto(
                sizeBytes != null ? sizeBytes : 0L,
                sizePretty != null ? sizePretty : "0 bytes",
                spreadLogRows,
                spreadLogBytes != null ? spreadLogBytes : 0L,
                spreadLogSizePretty != null ? spreadLogSizePretty : "0 bytes"
        );
    }

    /**
     * Clears historical spread opportunities only. Leaves tracked pairs and fees intact.
     */
    @Transactional
    public DatabaseFlushResultDto flushSpreadLog() {
        long deletedRows = spreadLogRepository.count();
        spreadLogRepository.deleteAllInBatch();
        return new DatabaseFlushResultDto(deletedRows, getStats());
    }
}
