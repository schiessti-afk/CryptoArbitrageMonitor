import { Injectable, effect, signal } from '@angular/core';
import {
  DEFAULT_DISABLED_USDT_EXTENDED,
  isUsdtExtended,
  isUsdtMajor,
  USDT_MAJOR_SYMBOLS,
} from '../utils/market-groups';

export interface DashboardSettings {
  version: 2;
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
  version: 2,
  disabledExchanges: [],
  disabledSymbols: [...DEFAULT_DISABLED_USDT_EXTENDED],
  minNetSpreadPercent: 0,
  hideBelowThreshold: false,
  theme: 'dark',
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
  const version = (raw as Record<string, unknown>)['version'];
  if (version === 1) {
    // v1 default was "show everything" — migrate to major-5 USDT default.
    if (merged.disabledSymbols.length === 0) {
      merged.disabledSymbols = [...DEFAULT_DISABLED_USDT_EXTENDED];
    }
    merged.version = 2;
  } else if (merged.version !== 2) {
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

  /** Enable only the five major USDT markets; hide extended USDT; leave USD unchanged. */
  showUsdtMajorOnly(allSymbols: string[]) {
    const disabled = new Set(this.settings().disabledSymbols.filter(s => !s.endsWith('/USDT')));
    for (const symbol of allSymbols) {
      if (isUsdtExtended(symbol)) {
        disabled.add(symbol);
      } else if (isUsdtMajor(symbol)) {
        disabled.delete(symbol);
      }
    }
    for (const major of USDT_MAJOR_SYMBOLS) {
      disabled.delete(major);
    }
    this.update({ disabledSymbols: Array.from(disabled).sort() });
  }

  /** Enable every tracked USDT market. */
  showAllUsdt(allSymbols: string[]) {
    const disabled = this.settings().disabledSymbols.filter(s => !s.endsWith('/USDT'));
    this.update({ disabledSymbols: disabled });
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
