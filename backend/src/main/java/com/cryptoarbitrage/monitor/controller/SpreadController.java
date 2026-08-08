package com.cryptoarbitrage.monitor.controller;

import com.cryptoarbitrage.monitor.config.AppProperties;
import com.cryptoarbitrage.monitor.dto.ExchangeStatusDto;
import com.cryptoarbitrage.monitor.dto.FeeDto;
import com.cryptoarbitrage.monitor.dto.PairDto;
import com.cryptoarbitrage.monitor.dto.SpreadDto;
import com.cryptoarbitrage.monitor.exchange.Exchange;
import com.cryptoarbitrage.monitor.model.SpreadLog;
import com.cryptoarbitrage.monitor.repository.SpreadLogRepository;
import com.cryptoarbitrage.monitor.repository.TrackedPairRepository;
import com.cryptoarbitrage.monitor.service.ExchangeAvailabilityStore;
import com.cryptoarbitrage.monitor.service.FeeService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SpreadController {

    private final TrackedPairRepository trackedPairRepository;
    private final FeeService feeService;
    private final ExchangeAvailabilityStore availabilityStore;
    private final SpreadLogRepository spreadLogRepository;
    private final AppProperties appProperties;

    public SpreadController(
            TrackedPairRepository trackedPairRepository,
            FeeService feeService,
            ExchangeAvailabilityStore availabilityStore,
            SpreadLogRepository spreadLogRepository,
            AppProperties appProperties
    ) {
        this.trackedPairRepository = trackedPairRepository;
        this.feeService = feeService;
        this.availabilityStore = availabilityStore;
        this.spreadLogRepository = spreadLogRepository;
        this.appProperties = appProperties;
    }

    /**
     * GET /api/pairs — list all tracked pairs
     */
    @GetMapping("/pairs")
    public ResponseEntity<List<PairDto>> getPairs() {
        return ResponseEntity.ok(
                trackedPairRepository.findAll().stream()
                        .map(PairDto::from)
                        .collect(Collectors.toList())
        );
    }

    /**
     * GET /api/exchanges — exchange status and freshness
     */
    @GetMapping("/exchanges")
    public ResponseEntity<List<ExchangeStatusDto>> getExchanges() {
        long freshnessWindow = appProperties.getPolling().getFreshnessWindowMs();
        List<ExchangeStatusDto> statuses = Arrays.stream(Exchange.values())
                .map(exchange -> {
                    Instant lastUpdate = availabilityStore.getLastReceivedAt(exchange);
                    boolean isFresh = availabilityStore.isFresh(exchange, freshnessWindow);
                    return ExchangeStatusDto.from(exchange, lastUpdate, isFresh);
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(statuses);
    }

    /**
     * GET /api/fees — current taker fees
     */
    @GetMapping("/fees")
    public ResponseEntity<List<FeeDto>> getFees() {
        var fees = feeService.getAllFees();
        List<FeeDto> feeDtos = fees.entrySet().stream()
                .map(entry -> new FeeDto(entry.getKey().name(), entry.getValue(), Instant.now()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(feeDtos);
    }

    /**
     * GET /api/spreads/latest — latest best opportunity per symbol
     */
    @GetMapping("/spreads/latest")
    public ResponseEntity<List<SpreadDto>> getSpreadsLatest() {
        List<SpreadDto> latestSpreads = new ArrayList<>();

        for (var pair : trackedPairRepository.findByActiveTrue()) {
            List<SpreadLog> logs = spreadLogRepository.findLatestBySymbol(
                    pair.getSymbol(),
                    PageRequest.of(0, 1)
            );
            if (!logs.isEmpty()) {
                latestSpreads.add(SpreadDto.from(logs.get(0)));
            }
        }

        return ResponseEntity.ok(latestSpreads);
    }

    /**
     * GET /api/spreads/history?limit=100&from=2026-08-08T00:00:00Z&to=2026-08-09T00:00:00Z
     * Required: limit
     * Optional: from, to
     */
    @GetMapping("/spreads/history")
    public ResponseEntity<?> getSpreadsHistory(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        // Validate limit is present and within bounds
        if (limit == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "limit is required"));
        }
        if (limit < 1 || limit > 10000) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "limit must be between 1 and 10000")
            );
        }

        Pageable pageable = PageRequest.of(0, limit);

        List<SpreadLog> results;
        if (from != null && to != null) {
            results = spreadLogRepository.findByCalculatedAtBetween(from, to, pageable);
        } else if (from != null) {
            results = spreadLogRepository.findByCalculatedAtAfter(from, pageable);
        } else if (to != null) {
            results = spreadLogRepository.findByCalculatedAtBefore(to, pageable);
        } else {
            // No time filter, just get latest N rows
            results = spreadLogRepository.findAll(PageRequest.of(0, limit)).getContent();
        }

        List<SpreadDto> dtos = results.stream()
                .map(SpreadDto::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }
}
