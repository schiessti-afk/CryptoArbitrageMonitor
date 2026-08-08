package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.config.AppProperties;
import com.cryptoarbitrage.monitor.dto.ExchangeStatusDto;
import com.cryptoarbitrage.monitor.dto.SpreadDto;
import com.cryptoarbitrage.monitor.dto.SpreadSnapshotDto;
import com.cryptoarbitrage.monitor.exchange.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;

/**
 * Publishes the full spread matrix to WebSocket subscribers on each poll cycle.
 */
@Service
public class SpreadPublisher {

    private static final Logger log = LoggerFactory.getLogger(SpreadPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final ExchangeAvailabilityStore availabilityStore;
    private final AppProperties appProperties;

    public SpreadPublisher(
            SimpMessagingTemplate messagingTemplate,
            ExchangeAvailabilityStore availabilityStore,
            AppProperties appProperties
    ) {
        this.messagingTemplate = messagingTemplate;
        this.availabilityStore = availabilityStore;
        this.appProperties = appProperties;
    }

    /**
     * Publish one snapshot containing the full matrix, best opportunities, and exchange health.
     * Called once per poll cycle, after the DB write completes.
     */
    public void publishSnapshot(SpreadCalculationService.CalculationResult result, Instant calculatedAt) {
        long freshnessWindowMs = appProperties.getPolling().getFreshnessWindowMs();

        int freshCount = (int) Arrays.stream(Exchange.values())
                .filter(ex -> availabilityStore.isFresh(ex, freshnessWindowMs))
                .count();

        boolean live = freshCount >= 2;

        SpreadSnapshotDto snapshot = new SpreadSnapshotDto(
                calculatedAt,
                result.fullMatrix.stream()
                        .map(SpreadDto::from)
                        .toList(),
                result.bestPerSymbol.values().stream()
                        .map(SpreadDto::from)
                        .toList(),
                Arrays.stream(Exchange.values())
                        .map(ex -> ExchangeStatusDto.from(
                                ex,
                                availabilityStore.getLastReceivedAt(ex),
                                availabilityStore.isFresh(ex, freshnessWindowMs)
                        ))
                        .toList(),
                freshCount,
                live
        );

        try {
            messagingTemplate.convertAndSend("/topic/spreads", snapshot);
            log.debug("Published snapshot with {} opportunities, {} fresh exchanges",
                    snapshot.matrix().size(), freshCount);
        } catch (Exception e) {
            log.warn("Failed to publish spread snapshot: {}", e.getMessage());
        }
    }
}
