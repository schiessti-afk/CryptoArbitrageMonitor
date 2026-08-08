package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.config.AppProperties;
import com.cryptoarbitrage.monitor.exchange.Exchange;
import com.cryptoarbitrage.monitor.exchange.ExchangeAdapter;
import com.cryptoarbitrage.monitor.exchange.PriceTicker;
import com.cryptoarbitrage.monitor.model.SpreadLog;
import com.cryptoarbitrage.monitor.model.TrackedPair;
import com.cryptoarbitrage.monitor.repository.SpreadLogRepository;
import com.cryptoarbitrage.monitor.repository.TrackedPairRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates polling cycles: fetch tickers, calculate spreads, persist best, publish.
 * Non-overlapping cycles via in-flight guard (AtomicBoolean).
 */
@Service
public class PollOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(PollOrchestrationService.class);

    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    private final List<ExchangeAdapter> adapters;
    private final SpreadCalculationService calculationService;
    private final FeeService feeService;
    private final ExchangeAvailabilityStore availabilityStore;
    private final MarketSnapshotStore snapshotStore;
    private final SpreadPublisher spreadPublisher;
    private final TrackedPairRepository trackedPairRepository;
    private final SpreadLogRepository spreadLogRepository;
    private final AppProperties appProperties;
    private final ClientPollPreferenceService pollPreferenceService;

    @Autowired
    public PollOrchestrationService(
            List<ExchangeAdapter> adapters,
            SpreadCalculationService calculationService,
            FeeService feeService,
            ExchangeAvailabilityStore availabilityStore,
            MarketSnapshotStore snapshotStore,
            SpreadPublisher spreadPublisher,
            TrackedPairRepository trackedPairRepository,
            SpreadLogRepository spreadLogRepository,
            AppProperties appProperties,
            ClientPollPreferenceService pollPreferenceService
    ) {
        this.adapters = adapters;
        this.calculationService = calculationService;
        this.feeService = feeService;
        this.availabilityStore = availabilityStore;
        this.snapshotStore = snapshotStore;
        this.spreadPublisher = spreadPublisher;
        this.trackedPairRepository = trackedPairRepository;
        this.spreadLogRepository = spreadLogRepository;
        this.appProperties = appProperties;
        this.pollPreferenceService = pollPreferenceService;
    }

    @Scheduled(fixedDelayString = "${app.polling.interval-ms:3000}")
    public void pollCycle() {
        // In-flight guard: skip if already running
        if (!inFlight.compareAndSet(false, true)) {
            log.debug("Poll cycle already in flight, skipping");
            return;
        }

        try {
            executePollCycle();
        } catch (Exception e) {
            log.error("Poll cycle failed", e);
        } finally {
            inFlight.set(false);
        }
    }

    private void executePollCycle() {
        long startTime = System.currentTimeMillis();
        Instant cycleTimestamp = Instant.now();
        log.debug("Starting poll cycle");

        // Step 1: Get active tracked pairs limited to client-selected markets
        List<TrackedPair> activePairs = trackedPairRepository.findByActiveTrue();
        if (activePairs.isEmpty()) {
            log.warn("No active tracked pairs");
            return;
        }

        List<String> pollSymbols = pollPreferenceService.resolvePollSymbols(
                activePairs.stream().map(TrackedPair::getSymbol).toList());
        if (pollSymbols.isEmpty()) {
            log.warn("No markets selected for polling");
            return;
        }

        Set<String> pollSet = Set.copyOf(pollSymbols);
        List<TrackedPair> pairsToPoll = activePairs.stream()
                .filter(pair -> pollSet.contains(pair.getSymbol()))
                .toList();

        // Step 2: Fetch tickers for selected markets only
        Map<String, List<PriceTicker>> tickers = fetchTickersInParallel(pairsToPoll);

        if (tickers.isEmpty()) {
            log.warn("No tickers received from any exchange");
            return;
        }

        // Step 3: Calculate spreads
        Map<Exchange, BigDecimal> fees = feeService.getAllFees();
        SpreadCalculationService.CalculationResult result = calculationService.calculateSpreads(tickers, fees);

        if (result.fullMatrix.isEmpty()) {
            log.warn("No spread opportunities calculated");
            return;
        }

        log.debug("Calculated {} opportunities from {} tickers", result.fullMatrix.size(), tickers.size());

        // Step 4: Persist best opportunity per symbol
        persistBestOpportunities(result.bestPerSymbol, cycleTimestamp);

        // Step 5: Store full matrix for live publishing
        snapshotStore.update(result.fullMatrix);

        // Step 6: Publish to WebSocket subscribers
        try {
            spreadPublisher.publishSnapshot(result, cycleTimestamp, pollSymbols);
        } catch (Exception e) {
            log.warn("Failed to publish snapshot: {}", e.getMessage());
        }

        long elapsedMs = System.currentTimeMillis() - startTime;
        log.info("Poll cycle completed in {}ms. Best opportunities: {}, Full matrix: {}",
                elapsedMs, result.bestPerSymbol.size(), result.fullMatrix.size());
    }

    /**
     * Fetches tickers per adapter using batch endpoints where available. Adapters that don't
     * offer a given symbol (see {@link ExchangeAdapter#supports}) are skipped before any HTTP
     * call is made.
     */
    private Map<String, List<PriceTicker>> fetchTickersInParallel(List<TrackedPair> activePairs) {
        List<String> symbols = activePairs.stream().map(TrackedPair::getSymbol).toList();

        List<Mono<List<PriceTicker>>> adapterRequests = new ArrayList<>();
        for (ExchangeAdapter adapter : adapters) {
            List<String> supported = resolveSymbolsForAdapter(adapter, symbols);
            if (supported.isEmpty()) {
                continue;
            }
            adapterRequests.add(
                    adapter.getTickers(supported)
                            .doOnNext(ticker -> availabilityStore.recordSuccess(ticker.exchange(), ticker.symbol()))
                            .onErrorContinue((e, obj) -> log.warn("Adapter {} ticker error: {}",
                                    adapter.getExchange(), e.getMessage()))
                            .collectList()
                            .onErrorResume(e -> {
                                log.warn("Adapter {} batch failed: {}", adapter.getExchange(), e.getMessage());
                                return Mono.just(List.of());
                            })
            );
        }

        List<PriceTicker> allTickers = Flux.fromIterable(adapterRequests)
                .flatMap(mono -> mono)
                .flatMapIterable(list -> list)
                .collectList()
                .block();

        Map<String, List<PriceTicker>> bySymbol = new HashMap<>();
        if (allTickers != null) {
            for (PriceTicker ticker : allTickers) {
                bySymbol.computeIfAbsent(ticker.symbol(), k -> new ArrayList<>()).add(ticker);
            }
        }
        return bySymbol;
    }

    private List<String> resolveSymbolsForAdapter(ExchangeAdapter adapter, List<String> symbols) {
        return symbols.stream().filter(adapter::supports).toList();
    }

    private void persistBestOpportunities(Map<String, SpreadCalculationService.SpreadOpportunity> bestPerSymbol, Instant cycleTimestamp) {
        for (SpreadCalculationService.SpreadOpportunity opp : bestPerSymbol.values()) {
            try {
                SpreadLog spreadLog = new SpreadLog();
                spreadLog.setSymbol(opp.symbol);
                spreadLog.setBuyExchange(opp.buyExchange.name());
                spreadLog.setSellExchange(opp.sellExchange.name());
                spreadLog.setBuyPrice(opp.buyPrice);
                spreadLog.setSellPrice(opp.sellPrice);
                spreadLog.setRawSpreadPercent(opp.rawSpreadPercent);
                spreadLog.setNetSpreadPercent(opp.netSpreadPercent);
                spreadLog.setCalculatedAt(cycleTimestamp);

                spreadLogRepository.save(spreadLog);
                log.debug("Persisted best opportunity for {}: {} via {}->{}",
                        opp.symbol, opp.netSpreadPercent, opp.buyExchange, opp.sellExchange);
            } catch (Exception e) {
                log.warn("Failed to persist spread log for {}: {}", opp.symbol, e.getMessage());
            }
        }
    }
}
