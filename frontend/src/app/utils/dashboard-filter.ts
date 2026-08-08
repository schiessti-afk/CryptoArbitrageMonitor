import { SpreadOpportunity, ExchangeStatus, SymbolCoverage } from '../models/spread.model';
import { DashboardSettings } from '../services/settings.service';

export type OpportunityQuickFilter = 'all' | 'positive' | 'aboveThreshold';

export interface FilterContext {
  quoteAsset: string;
  settings: DashboardSettings;
  quickFilter: OpportunityQuickFilter;
  showBothDirections: boolean;
}

export function matchesQuoteAsset(symbol: string, quoteAsset: string): boolean {
  return symbol.endsWith('/' + quoteAsset);
}

export function filterMatrixBySettings(
  matrix: SpreadOpportunity[],
  settings: DashboardSettings
): SpreadOpportunity[] {
  const disabledExchanges = new Set(settings.disabledExchanges);
  const disabledSymbols = new Set(settings.disabledSymbols);
  const threshold = settings.minNetSpreadPercent;

  return matrix.filter(row => {
    if (disabledExchanges.has(row.buyExchange) || disabledExchanges.has(row.sellExchange)) {
      return false;
    }
    if (disabledSymbols.has(row.symbol)) {
      return false;
    }
    if (settings.hideBelowThreshold && row.netSpreadPercent < threshold) {
      return false;
    }
    return true;
  });
}

export function isBelowThreshold(row: SpreadOpportunity, settings: DashboardSettings): boolean {
  return row.netSpreadPercent < settings.minNetSpreadPercent;
}

export function collapseMirroredRoutes(rows: SpreadOpportunity[]): SpreadOpportunity[] {
  const bestByPair = new Map<string, SpreadOpportunity>();
  for (const row of rows) {
    const venues = [row.buyExchange, row.sellExchange].sort().join('|');
    const key = `${row.symbol}|${venues}`;
    const existing = bestByPair.get(key);
    if (!existing || row.netSpreadPercent > existing.netSpreadPercent) {
      bestByPair.set(key, row);
    }
  }
  return Array.from(bestByPair.values());
}

export function recomputeBestPerSymbol(matrix: SpreadOpportunity[]): SpreadOpportunity[] {
  const best = new Map<string, SpreadOpportunity>();
  for (const row of matrix) {
    const current = best.get(row.symbol);
    if (!current || row.netSpreadPercent > current.netSpreadPercent) {
      best.set(row.symbol, row);
    }
  }
  return Array.from(best.values());
}

export function applyOpportunityQuickFilter(
  rows: SpreadOpportunity[],
  quickFilter: OpportunityQuickFilter,
  settings: DashboardSettings
): SpreadOpportunity[] {
  switch (quickFilter) {
    case 'positive':
      return rows.filter(r => r.netSpreadPercent > 0.001);
    case 'aboveThreshold':
      return rows.filter(r => r.netSpreadPercent >= settings.minNetSpreadPercent);
    default:
      return rows;
  }
}

export function buildRankedOpportunities(
  backendBest: SpreadOpportunity[],
  visibleMatrix: SpreadOpportunity[],
  settings: DashboardSettings,
  quickFilter: OpportunityQuickFilter
): SpreadOpportunity[] {
  const best =
    settings.disabledExchanges.length === 0
      ? backendBest.filter(o =>
          visibleMatrix.some(r => r.symbol === o.symbol && !settings.disabledSymbols.includes(o.symbol))
        )
      : recomputeBestPerSymbol(visibleMatrix);

  return applyOpportunityQuickFilter(
    [...best].sort((a, b) => b.netSpreadPercent - a.netSpreadPercent),
    quickFilter,
    settings
  );
}

export function computeVisibleLive(
  exchanges: ExchangeStatus[],
  quoteAsset: string,
  settings: DashboardSettings,
  fallbackLive: boolean,
  freshnessWindowMs: number,
  nowMs: number
): boolean {
  if (settings.disabledExchanges.length === 0) {
    return fallbackLive;
  }

  const visibleFresh = exchanges.filter(ex =>
    ex.offeredQuoteAssets?.includes(quoteAsset) &&
    !settings.disabledExchanges.includes(ex.exchange) &&
    isExchangeFresh(ex, freshnessWindowMs, nowMs)
  );

  return visibleFresh.length >= 2;
}

function isExchangeFresh(ex: ExchangeStatus, freshnessWindowMs: number, nowMs: number): boolean {
  if (!ex.lastUpdate) {
    return false;
  }
  return nowMs - new Date(ex.lastUpdate).getTime() < freshnessWindowMs;
}

export function countVisibleFreshExchanges(
  exchanges: ExchangeStatus[],
  quoteAsset: string,
  settings: DashboardSettings,
  freshnessWindowMs: number,
  nowMs: number
): { fresh: number; total: number } {
  const relevant = exchanges.filter(ex => ex.offeredQuoteAssets?.includes(quoteAsset));
  const visible = relevant.filter(ex => !settings.disabledExchanges.includes(ex.exchange));
  const fresh = visible.filter(ex => isExchangeFresh(ex, freshnessWindowMs, nowMs)).length;
  return { fresh, total: visible.length };
}

export function getCoverageForQuote(
  coverage: SymbolCoverage[] | undefined,
  quoteAsset: string
): SymbolCoverage[] {
  return (coverage ?? []).filter(c => c.quoteAsset === quoteAsset);
}

export function describeFilterChips(settings: DashboardSettings): string[] {
  const chips: string[] = [];
  if (settings.disabledExchanges.length > 0) {
    chips.push(`${settings.disabledExchanges.length} venue${settings.disabledExchanges.length === 1 ? '' : 's'} hidden`);
  }
  if (settings.disabledSymbols.length > 0) {
    chips.push(`${settings.disabledSymbols.length} market${settings.disabledSymbols.length === 1 ? '' : 's'} hidden`);
  }
  if (settings.minNetSpreadPercent > 0) {
    chips.push(`net ≥ ${settings.minNetSpreadPercent}%`);
  }
  if (settings.hideBelowThreshold && settings.minNetSpreadPercent > 0) {
    chips.push('hiding below threshold');
  }
  return chips;
}
