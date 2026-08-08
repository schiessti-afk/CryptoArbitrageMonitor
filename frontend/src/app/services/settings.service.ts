import { Injectable, effect, signal } from '@angular/core';

export interface DashboardSettings {
  version: 1;
  disabledExchanges: string[];
  disabledSymbols: string[];
  minNetSpreadPercent: number;
  hideBelowThreshold: boolean;
  theme: 'system' | 'light' | 'dark';
  density: 'comfortable' | 'compact';
  freshnessWindowMsOverride: number | null;
  defaultNotionalOverride: number | null;
}

export const DEFAULT_SETTINGS: DashboardSettings = {
  version: 1,
  disabledExchanges: [],
  disabledSymbols: [],
  minNetSpreadPercent: 0,
  hideBelowThreshold: false,
  theme: 'system',
  density: 'comfortable',
  freshnessWindowMsOverride: null,
  defaultNotionalOverride: null,
};

const STORAGE_KEY = 'crypto-arbitrage-monitor:settings';
const KNOWN_KEYS = new Set(Object.keys(DEFAULT_SETTINGS));

export function mergeSettings(raw: unknown): DashboardSettings {
  const merged = { ...DEFAULT_SETTINGS };
  if (!raw || typeof raw !== 'object') {
    return merged;
  }
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    if (!KNOWN_KEYS.has(key)) {
      continue;
    }
    (merged as Record<string, unknown>)[key] = value;
  }
  if (merged.version !== 1) {
    return { ...DEFAULT_SETTINGS };
  }
  return merged;
}

export function readStoredSettings(): DashboardSettings {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (!stored) {
      return { ...DEFAULT_SETTINGS };
    }
    return mergeSettings(JSON.parse(stored));
  } catch {
    return { ...DEFAULT_SETTINGS };
  }
}

function writeStoredSettings(settings: DashboardSettings): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
  } catch {
    // Non-fatal — settings still apply for this session.
  }
}

function clearStoredSettings(): void {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // ignore
  }
}

@Injectable({
  providedIn: 'root',
})
export class SettingsService {
  readonly settings = signal<DashboardSettings>(readStoredSettings());

  constructor() {
    effect(() => {
      this.applyTheme(this.settings().theme);
    });
  }

  update(partial: Partial<DashboardSettings>) {
    this.settings.update(current => {
      const next = { ...current, ...partial };
      writeStoredSettings(next);
      return next;
    });
  }

  resetToDefaults() {
    clearStoredSettings();
    this.settings.set({ ...DEFAULT_SETTINGS });
  }

  toggleExchange(exchange: string, enabled: boolean) {
    this.settings.update(current => {
      const disabled = new Set(current.disabledExchanges);
      if (enabled) {
        disabled.delete(exchange);
      } else {
        disabled.add(exchange);
      }
      const next = { ...current, disabledExchanges: Array.from(disabled).sort() };
      writeStoredSettings(next);
      return next;
    });
  }

  toggleSymbol(symbol: string, enabled: boolean) {
    this.settings.update(current => {
      const disabled = new Set(current.disabledSymbols);
      if (enabled) {
        disabled.delete(symbol);
      } else {
        disabled.add(symbol);
      }
      const next = { ...current, disabledSymbols: Array.from(disabled).sort() };
      writeStoredSettings(next);
      return next;
    });
  }

  showAllVenues() {
    this.update({ disabledExchanges: [] });
  }

  showAllSymbols() {
    this.update({ disabledSymbols: [] });
  }

  clearFilters() {
    this.update({
      disabledExchanges: [],
      disabledSymbols: [],
      minNetSpreadPercent: 0,
      hideBelowThreshold: false,
    });
  }

  private applyTheme(theme: DashboardSettings['theme']) {
    const root = document.documentElement;
    root.removeAttribute('data-theme');
    if (theme === 'dark') {
      root.setAttribute('data-theme', 'dark');
    } else if (theme === 'light') {
      root.setAttribute('data-theme', 'light');
    }
  }
}
