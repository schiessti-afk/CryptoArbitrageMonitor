import {
  buildDepthLevels,
  findFillIndex,
  formatCompact,
  liquidityChipClass,
  summarizeLiquidity,
} from './liquidity';
import { SpreadOpportunity } from '../models/spread.model';

describe('liquidity utils', () => {
  const opp = (partial: Partial<SpreadOpportunity> = {}): SpreadOpportunity => ({
    symbol: 'BTC/USDT',
    buyExchange: 'BINANCE',
    sellExchange: 'KRAKEN',
    buyPrice: 100,
    sellPrice: 101,
    rawSpreadPercent: 1,
    netSpreadPercent: 0.5,
    buyAskSize: 20,
    sellBidSize: 15,
    ...partial,
  });

  it('summarizeLiquidity marks unknown when size missing', () => {
    const summary = summarizeLiquidity(opp({ buyAskSize: undefined }), 1000, 'USDT');
    expect(summary.grade).toBe('unknown');
    expect(summary.label).toBe('Unknown');
  });

  it('summarizeLiquidity grades thin ok and deep', () => {
    expect(summarizeLiquidity(opp({ buyAskSize: 5, sellBidSize: 5 }), 1000, 'USDT').grade).toBe('thin');
    expect(summarizeLiquidity(opp({ buyAskSize: 15, sellBidSize: 15 }), 1000, 'USDT').grade).toBe('ok');
    expect(summarizeLiquidity(opp({ buyAskSize: 60, sellBidSize: 60 }), 1000, 'USDT').grade).toBe('deep');
  });

  it('formatCompact uses K M B thresholds', () => {
    expect(formatCompact(12.34)).toBe('12.34');
    expect(formatCompact(1500)).toBe('1.5K');
    expect(formatCompact(2_500_000)).toBe('2.5M');
    expect(formatCompact(3_200_000_000)).toBe('3.2B');
  });

  it('buildDepthLevels accumulates quote and bar percent', () => {
    const levels = buildDepthLevels(
      [
        { price: 100, size: 1 },
        { price: 101, size: 2 },
      ],
      200
    );
    expect(levels[0].quoteNotional).toBe(100);
    expect(levels[0].cumulativeQuote).toBe(100);
    expect(levels[0].barPercent).toBe(50);
    expect(levels[1].cumulativeQuote).toBe(302);
  });

  it('findFillIndex returns first covering level or null', () => {
    const levels = buildDepthLevels(
      [
        { price: 100, size: 1 },
        { price: 100, size: 2 },
      ],
      1000
    );
    expect(findFillIndex(levels, 150)).toBe(1);
    expect(findFillIndex(levels, 10_000)).toBeNull();
  });

  it('liquidityChipClass maps grades', () => {
    expect(liquidityChipClass('thin')).toBe('chip chip-liq-thin');
    expect(liquidityChipClass('deep')).toBe('chip chip-liq-deep');
    expect(liquidityChipClass('ok')).toBe('chip chip-liq-ok');
    expect(liquidityChipClass('unknown')).toBe('chip chip-muted');
  });
});
