package com.cryptoarbitrage.monitor.repository;

import com.cryptoarbitrage.monitor.model.SpreadLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SpreadLogRepository extends JpaRepository<SpreadLog, Long> {

    /**
     * Find the latest spread log for a given symbol.
     * Ordered by calculatedAt DESC, limited to 1.
     */
    @Query("SELECT sl FROM SpreadLog sl WHERE sl.symbol = :symbol ORDER BY sl.calculatedAt DESC")
    List<SpreadLog> findLatestBySymbol(@Param("symbol") String symbol, Pageable pageable);

    /**
     * Find all spread logs within a time range, ordered by calculatedAt DESC.
     */
    @Query("SELECT sl FROM SpreadLog sl WHERE sl.calculatedAt >= :from AND sl.calculatedAt <= :to ORDER BY sl.calculatedAt DESC")
    List<SpreadLog> findByCalculatedAtBetween(
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable
    );

    /**
     * Find all spread logs after a given time, ordered by calculatedAt DESC.
     */
    @Query("SELECT sl FROM SpreadLog sl WHERE sl.calculatedAt >= :from ORDER BY sl.calculatedAt DESC")
    List<SpreadLog> findByCalculatedAtAfter(
            @Param("from") Instant from,
            Pageable pageable
    );

    /**
     * Find all spread logs before a given time, ordered by calculatedAt DESC.
     */
    @Query("SELECT sl FROM SpreadLog sl WHERE sl.calculatedAt <= :to ORDER BY sl.calculatedAt DESC")
    List<SpreadLog> findByCalculatedAtBefore(
            @Param("to") Instant to,
            Pageable pageable
    );
}
