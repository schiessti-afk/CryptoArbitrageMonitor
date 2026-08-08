package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.config.AppProperties;
import com.cryptoarbitrage.monitor.config.ExchangeProperties;
import com.cryptoarbitrage.monitor.dto.ExchangeStatusDto;
import com.cryptoarbitrage.monitor.dto.SpreadDto;
import com.cryptoarbitrage.monitor.dto.SpreadSnapshotDto;
import com.cryptoarbitrage.monitor.exchange.Exchange;
import com.cryptoarbitrage.monitor.model.TrackedPair;
import com.cryptoarbitrage.monitor.repository.TrackedPairRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Publishes the full spread matrix to WebSocket subscribers on each poll cycle.
 */
@Service
public class SpreadPublisher {

    private static final Logger log = LoggerFactory.getLogger(SpreadPublisher.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final ExchangeAvailabilityStore availabilityStore;
    private final AppProperties appProperties;
    private final ExchangeProperties exchangeProperties;
    private final TrackedPairRepository trackedPairRepository;

    public SpreadPublisher(
            SimpMessagingTemplate messagingTemplate,
            ExchangeAvailabilityStore availabilityStore,
            AppProperties appProperties,
            ExchangeProperties exchangeProperties,
            TrackedPairRepository trackedPairRepository
    ) {
        this.messagingTemplate = messagingTemplate;
        this.availabilityStore = availabilityStore;
        this.appProperties = appProperties;
        this.exchangeProperties = exchangeProperties;
        this.trackedPairRepository = trackedPairRepository;
    }

    /**
     * Publish one snapshot containing the full matrix, best opportunities, and exchange health.
     * Called once per poll cycle, after the DB write completes.
     */
    public void publishSnapshot(SpreadCalculationService.CalculationResult result, Instant calculatedAt) {
        long freshnessWindowMs = appProperties.getPolling().getFreshnessWindowMs();

        // Legacy global fields — kept for compatibility with any consumer that only cares about
        // "is anything at all live" rather than per-quote-asset health.
        int freshCount = (int) Arrays.stream(Exchange.values())
                .filter(ex -> availabilityStore.isFreshAny(ex, freshnessWindowMs))
                .count();
        boolean live = freshCount >= 2;

        // Per-quote-asset freshness: a symbol's quote comes from its tracked_pair row (the
        // authoritative mapping — not inferred from live ticker data, so it's correct even when
        // a quote universe has zero fresh venues right now).
        Map<String, List<String>> symbolsByQuote = trackedPairRepository.findByActiveTrue().stream()
                .collect(Collectors.groupingBy(
                        TrackedPair::getQuoteCurrency,
                        Collectors.mapping(TrackedPair::getSymbol, Collectors.toList())
                ));

        Map<String, Integer> freshCountByQuote = new LinkedHashMap<>();
        Map<String, Boolean> liveByQuote = new LinkedHashMap<>();
        for (var entry : symbolsByQuote.entrySet()) {
            String quoteAsset = entry.getKey();
            int maxFreshForQuote = entry.getValue().stream()
                    .mapToInt(symbol -> availabilityStore.countFreshForSymbol(symbol, freshnessWindowMs))
                    .max()
                    .orElse(0);
            freshCountByQuote.put(quoteAsset, maxFreshForQuote);
            liveByQuote.put(quoteAsset, maxFreshForQuote >= 2);
        }

        List<ExchangeStatusDto> exchangeStatuses = Arrays.stream(Exchange.values())
                .map(ex -> ExchangeStatusDto.from(
                        ex,
                        availabilityStore.getLastReceivedAtAny(ex),
                        availabilityStore.isFreshAny(ex, freshnessWindowMs),
                        exchangeProperties.getOfferedQuoteAssets(ex).stream().sorted().toList()
                ))
                .toList();

        SpreadSnapshotDto snapshot = new SpreadSnapshotDto(
                calculatedAt,
                result.fullMatrix.stream()
                        .map(SpreadDto::from)
                        .toList(),
                result.bestPerSymbol.values().stream()
                        .map(SpreadDto::from)
                        .toList(),
                exchangeStatuses,
                freshCount,
                live,
                liveByQuote,
                freshCountByQuote
        );

        try {
            messagingTemplate.convertAndSend("/topic/spreads", snapshot);
            log.debug("Published snapshot with {} opportunities, liveByQuote={}",
                    snapshot.matrix().size(), liveByQuote);
        } catch (Exception e) {
            log.warn("Failed to publish spread snapshot: {}", e.getMessage());
        }
    }
}
