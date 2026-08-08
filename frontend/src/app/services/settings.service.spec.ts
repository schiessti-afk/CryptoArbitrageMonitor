import { mergeSettings, DEFAULT_SETTINGS } from './settings.service';
import {
  filterMatrixBySettings,
  recomputeBestPerSymbol,
  collapseMirroredRoutes,
  computeVisibleLive,
} from '../utils/dashboard-filter';
import { SpreadOpportunity, ExchangeStatus } from '../models/spread.model';

describe('mergeSettings', () => {
  it('defaults to dark theme', () => {
    expect(DEFAULT_SETTINGS.theme).toBe('dark');
  });

  it('returns defaults for corrupt JSON path', () => {
    expect(mergeSettings(null)).toEqual(DEFAULT_SETTINGS);
    expect(mergeSettings({ version: 2 })).toEqual(DEFAULT_SETTINGS);
  });

  it('drops unknown keys', () => {
    const merged = mergeSettings({ version: 1, theme: 'light', unknown: true });
    expect(merged.theme).toBe('light');
    expect((merged as any).unknown).toBeUndefined();
  });

  it('fills missing keys from defaults', () => {
    const merged = mergeSettings({ version: 1, theme: 'light' });
    expect(merged.theme).toBe('light');
    expect(merged.disabledExchanges).toEqual([]);
    expect(merged.minNetSpreadPercent).toBe(0);
  });

  it('uses dark when theme omitted from stored object', () => {
    const merged = mergeSettings({ version: 1 });
    expect(merged.theme).toBe('dark');
  });
});

describe('filter pipeline', () => {
  const row = (buy: string, sell: string, symbol = 'BTC/USD'): SpreadOpportunity => ({
    symbol,
    buyExchange: buy,
    sellExchange: sell,
    buyPrice: 100,
    sellPrice: 101,
    rawSpreadPercent: 1,
    netSpreadPercent: 0.5,
  });

  it('excludes row when either leg is disabled', () => {
    const matrix = [row('BINANCE', 'KRAKEN'), row('KRAKEN', 'COINBASE')];
    const filtered = filterMatrixBySettings(matrix, {
      ...DEFAULT_SETTINGS,
      disabledExchanges: ['KRAKEN'],
    });
    expect(filtered.length).toBe(0);
  });

  it('recomputeBestPerSymbol picks highest net when venues hidden', () => {
    const matrix = [
      { ...row('BINANCE', 'KRAKEN'), netSpreadPercent: 0.2 },
      { ...row('BINANCE', 'COINBASE'), netSpreadPercent: 0.8 },
      { ...row('COINBASE', 'BINANCE'), netSpreadPercent: -0.1 },
    ];
    const best = recomputeBestPerSymbol(matrix);
    expect(best.length).toBe(1);
    expect(best[0].netSpreadPercent).toBe(0.8);
  });

  it('collapseMirroredRoutes keeps better direction per venue pair', () => {
    const matrix = [
      { ...row('BINANCE', 'KRAKEN'), netSpreadPercent: 0.3 },
      { ...row('KRAKEN', 'BINANCE'), netSpreadPercent: -0.2 },
    ];
    const collapsed = collapseMirroredRoutes(matrix);
    expect(collapsed.length).toBe(1);
    expect(collapsed[0].buyExchange).toBe('BINANCE');
  });

  it('computeVisibleLive falls back when nothing disabled', () => {
    const live = computeVisibleLive([], 'USD', DEFAULT_SETTINGS, true, 10000, Date.now());
    expect(live).toBeTrue();
  });

  it('computeVisibleLive requires 2 fresh visible venues when filtered', () => {
    const exchanges: ExchangeStatus[] = [
      { exchange: 'BINANCE', available: true, freshness: 'FRESH', offeredQuoteAssets: ['USD'], lastUpdate: new Date().toISOString() },
      { exchange: 'KRAKEN', available: true, freshness: 'FRESH', offeredQuoteAssets: ['USD'], lastUpdate: new Date().toISOString() },
      { exchange: 'COINBASE', available: true, freshness: 'FRESH', offeredQuoteAssets: ['USD'], lastUpdate: new Date().toISOString() },
    ];
    const settings = { ...DEFAULT_SETTINGS, disabledExchanges: ['COINBASE', 'KRAKEN'] };
    const live = computeVisibleLive(exchanges, 'USD', settings, true, 10000, Date.now());
    expect(live).toBeFalse();
  });
});
