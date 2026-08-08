package com.cryptoarbitrage.monitor.repository;

import com.cryptoarbitrage.monitor.model.TrackedPair;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TrackedPairRepository extends JpaRepository<TrackedPair, Long> {
    Optional<TrackedPair> findBySymbol(String symbol);
    List<TrackedPair> findByActiveTrue();
}
