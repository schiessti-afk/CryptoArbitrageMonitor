package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.config.AppProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory client poll preferences (single-user local monitor). Last write wins.
 * Determines which tracked markets the backend actually polls each cycle.
 */
@Service
public class ClientPollPreferenceService {

    private final AppProperties appProperties;
    private final AtomicReference<PreferenceSnapshot> snapshot = new AtomicReference<>(
            new PreferenceSnapshot(List.of(), Instant.EPOCH)
    );

    public ClientPollPreferenceService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public void updateEnabledSymbols(List<String> enabledSymbols) {
        List<String> normalized = enabledSymbols == null
                ? List.of()
                : enabledSymbols.stream().sorted().distinct().toList();
        snapshot.set(new PreferenceSnapshot(normalized, Instant.now()));
    }

    public List<String> getEnabledSymbols() {
        return snapshot.get().enabledSymbols();
    }

    public boolean hasClientPreference() {
        return !snapshot.get().updatedAt().equals(Instant.EPOCH);
    }

    public Instant getUpdatedAt() {
        return snapshot.get().updatedAt();
    }

    /**
     * Symbols to poll this cycle: client-enabled markets when preferences exist,
     * otherwise all USD plus configured default USDT majors (pre-client bootstrap).
     */
    public List<String> resolvePollSymbols(Collection<String> allActiveSymbols) {
        List<String> activeList = allActiveSymbols.stream().sorted().distinct().toList();
        if (hasClientPreference()) {
            Set<String> enabledSet = Set.copyOf(getEnabledSymbols());
            return activeList.stream().filter(enabledSet::contains).toList();
        }
        Set<String> defaultMajors = Set.copyOf(appProperties.getPolling().getDefaultUsdtMajors());
        return activeList.stream()
                .filter(symbol -> symbol.endsWith("/USD") || defaultMajors.contains(symbol))
                .toList();
    }

    public record PreferenceSnapshot(List<String> enabledSymbols, Instant updatedAt) {
        public PreferenceSnapshot {
            enabledSymbols = enabledSymbols == null
                    ? List.of()
                    : Collections.unmodifiableList(enabledSymbols);
        }
    }
}
