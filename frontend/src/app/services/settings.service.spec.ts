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

  it('drops unknown keys and migrates v1 to v2', () => {
    const merged = mergeSettings({ version: 1, theme: 'light', unknown: true });
    expect(merged.theme).toBe('light');
    expect(merged.version).toBe(2);
    expect((merged as any).unknown).toBeUndefined();
  });

  it('fills missing keys from defaults on v1 migrate', () => {
    const merged = mergeSettings({ version: 1, theme: 'light' });
    expect(merged.theme).toBe('light');
    expect(merged.version).toBe(2);
    expect(merged.disabledExchanges).toEqual([]);
    expect(merged.minNetSpreadPercent).toBe(0);
    expect(merged.disabledSymbols.length).toBeGreaterThan(0);
  });

  it('migrates v1 show-all to major-5 USDT default', () => {
    const merged = mergeSettings({ version: 1 });
    expect(merged.version).toBe(2);
    expect(merged.theme).toBe('dark');
    expect(merged.disabledSymbols).toContain('BNB/USDT');
    expect(merged.disabledSymbols).not.toContain('BTC/USDT');
  });

  it('preserves custom disabled list on v1 migrate', () => {
    const merged = mergeSettings({ version: 1, disabledSymbols: ['ETH/USDT'] });
    expect(merged.disabledSymbols).toEqual(['ETH/USDT']);
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
