package com.cryptoarbitrage.monitor.service;

import com.cryptoarbitrage.monitor.config.AppProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
        List<String> normalized = normalizeEnabledOrder(enabledSymbols);
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
     * Symbols to poll this cycle: client-enabled markets in enable order when preferences exist,
     * otherwise USD pairs then configured default USDT majors (pre-client bootstrap).
     */
    public List<String> resolvePollSymbols(Collection<String> allActiveSymbols) {
        Set<String> activeSet = new HashSet<>(allActiveSymbols);
        if (hasClientPreference()) {
            return getEnabledSymbols().stream()
                    .filter(activeSet::contains)
                    .toList();
        }
        return defaultPollOrder(activeSet);
    }

    private List<String> defaultPollOrder(Set<String> activeSet) {
        List<String> result = new ArrayList<>();
        allActiveSymbolsSorted(activeSet).stream()
                .filter(symbol -> symbol.endsWith("/USD"))
                .forEach(result::add);
        for (String major : appProperties.getPolling().getDefaultUsdtMajors()) {
            if (activeSet.contains(major)) {
                result.add(major);
            }
        }
        return result;
    }

    private static List<String> allActiveSymbolsSorted(Set<String> activeSet) {
        return activeSet.stream().sorted().toList();
    }

    private static List<String> normalizeEnabledOrder(List<String> enabledSymbols) {
        if (enabledSymbols == null) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String symbol : enabledSymbols) {
            if (symbol != null && !symbol.isBlank()) {
                seen.add(symbol.trim());
            }
        }
        return List.copyOf(seen);
    }

    public record PreferenceSnapshot(List<String> enabledSymbols, Instant updatedAt) {
        public PreferenceSnapshot {
            enabledSymbols = enabledSymbols == null
                    ? List.of()
                    : Collections.unmodifiableList(enabledSymbols);
        }
    }
}
