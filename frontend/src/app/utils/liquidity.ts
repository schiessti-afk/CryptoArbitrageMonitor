import { SpreadOpportunity } from '../models/spread.model';

export type LiquidityGrade = 'thin' | 'ok' | 'deep' | 'unknown';

export interface LiquiditySummary {
  grade: LiquidityGrade;
  label: string;
  buyQuoteNotional: number | null;
  sellQuoteNotional: number | null;
  tooltip: string;
  maxVolume24h: number | null;
}

function topQuoteNotional(size: number | undefined, price: number): number | null {
  if (size == null || size <= 0 || price <= 0) return null;
  return size * price;
}

export function summarizeLiquidity(
  opp: SpreadOpportunity,
  notional: number,
  quoteAsset: string
): LiquiditySummary {
  const buyQuoteNotional = topQuoteNotional(opp.buyAskSize, opp.buyPrice);
  const sellQuoteNotional = topQuoteNotional(opp.sellBidSize, opp.sellPrice);

  if (buyQuoteNotional == null || sellQuoteNotional == null) {
    return {
      grade: 'unknown',
      label: 'Unknown',
      buyQuoteNotional,
      sellQuoteNotional,
      tooltip: 'Top-of-book size unavailable for one or both venues.',
      maxVolume24h: maxVolume24h(opp),
    };
  }

  const minLeg = Math.min(buyQuoteNotional, sellQuoteNotional);
  let grade: LiquidityGrade;
  let label: string;

  if (minLeg < notional) {
    grade = 'thin';
    label = 'Thin';
  } else if (minLeg >= notional * 5) {
    grade = 'deep';
    label = 'Deep';
  } else {
    grade = 'ok';
    label = 'OK';
  }

  const tooltip =
    `Buy ask: ${formatCompact(buyQuoteNotional)} ${quoteAsset} · ` +
    `Sell bid: ${formatCompact(sellQuoteNotional)} ${quoteAsset} · ` +
    `vs notional ${formatCompact(notional)} ${quoteAsset}`;

  return {
    grade,
    label,
    buyQuoteNotional,
    sellQuoteNotional,
    tooltip,
    maxVolume24h: maxVolume24h(opp),
  };
}

function maxVolume24h(opp: SpreadOpportunity): number | null {
  const values = [opp.buyQuoteVolume24h, opp.sellQuoteVolume24h].filter(
    (v): v is number => v != null && v > 0
  );
  if (values.length === 0) return null;
  return Math.max(...values);
}

export function formatCompact(value: number): string {
  const abs = Math.abs(value);
  if (abs >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(1)}B`;
  if (abs >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (abs >= 1_000) return `${(value / 1_000).toFixed(1)}K`;
  return value.toFixed(2);
}

export function liquidityChipClass(grade: LiquidityGrade): string {
  switch (grade) {
    case 'thin':
      return 'chip chip-liq-thin';
    case 'deep':
      return 'chip chip-liq-deep';
    case 'ok':
      return 'chip chip-liq-ok';
    default:
      return 'chip chip-muted';
  }
}

export interface DepthLevel {
  price: number;
  size: number;
  quoteNotional: number;
  cumulativeQuote: number;
  barPercent: number;
}

export function buildDepthLevels(
  levels: { price: number; size: number }[],
  maxQuote: number
): DepthLevel[] {
  let cumulative = 0;
  return levels.map(level => {
    const quoteNotional = level.price * level.size;
    cumulative += quoteNotional;
    return {
      price: level.price,
      size: level.size,
      quoteNotional,
      cumulativeQuote: cumulative,
      barPercent: maxQuote > 0 ? (quoteNotional / maxQuote) * 100 : 0,
    };
  });
}

export function findFillIndex(levels: DepthLevel[], notional: number): number | null {
  for (let i = 0; i < levels.length; i++) {
    if (levels[i].cumulativeQuote >= notional) {
      return i;
    }
  }
  return null;
}
