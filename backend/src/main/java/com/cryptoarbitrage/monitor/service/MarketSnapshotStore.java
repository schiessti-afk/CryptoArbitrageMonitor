package com.cryptoarbitrage.monitor.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for the latest market snapshot (full matrix).
 * Fetched by REST endpoints and Sprint 2 STOMP publisher.
 */
@Component
public class MarketSnapshotStore {

    private static class Snapshot {
        final List<SpreadCalculationService.SpreadOpportunity> matrix;
        final Instant timestamp;

        Snapshot(List<SpreadCalculationService.SpreadOpportunity> matrix, Instant timestamp) {
            this.matrix = matrix;
            this.timestamp = timestamp;
        }
    }

    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    /**
     * Store the latest full matrix.
     */
    public void update(List<SpreadCalculationService.SpreadOpportunity> matrix) {
        current.set(new Snapshot(matrix, Instant.now()));
    }

    /**
     * Fetch the latest full matrix, or empty list if none has been captured yet.
     */
    public List<SpreadCalculationService.SpreadOpportunity> getLatest() {
        Snapshot snap = current.get();
        if (snap == null) {
            return Collections.emptyList();
        }
        return snap.matrix;
    }

    /**
     * Fetch the timestamp of the latest snapshot.
     */
    public Instant getLatestTimestamp() {
        Snapshot snap = current.get();
        if (snap == null) {
            return null;
        }
        return snap.timestamp;
    }
}
