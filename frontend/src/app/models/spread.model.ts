export interface SpreadOpportunity {
  symbol: string;
  buyExchange: string;
  buyNativeSymbol?: string;
  buyQuoteAsset?: string;
  sellExchange: string;
  sellNativeSymbol?: string;
  sellQuoteAsset?: string;
  buyPrice: number;
  sellPrice: number;
  rawSpreadPercent: number;
  netSpreadPercent: number;
  calculatedAt?: string;
}

export type ExchangeFreshness = 'FRESH' | 'STALE' | 'NEVER';

export interface ExchangeStatus {
  exchange: string;
  available: boolean;
  lastUpdate?: string;
  freshness: ExchangeFreshness;
  /** Quote assets this venue lists at all (e.g. ["USD","USDT"]). Used to hide a venue's chip
   *  when the selected quote asset isn't one it offers, rather than showing it as broken. */
  offeredQuoteAssets: string[];
}

export interface SymbolCoverage {
  symbol: string;
  quoteAsset: string;
  configuredVenues: number;
  freshVenues: number;
}

export interface SpreadSnapshot {
  calculatedAt: string;
  matrix: SpreadOpportunity[];
  bestPerSymbol: SpreadOpportunity[];
  exchanges: ExchangeStatus[];
  freshExchangeCount: number;
  live: boolean;
  /** Per-quote-asset LIVE flag (>=2 fresh venues for that quote's symbols). A venue outage
   *  confined to one quote universe must not make the other read as LIVE incorrectly, or vice
   *  versa — this is what the global `live` field above cannot express. */
  liveByQuote: Record<string, boolean>;
  freshCountByQuote: Record<string, number>;
  coverage?: SymbolCoverage[];
}

export interface Pair {
  id: number;
  symbol: string;
  baseCurrency: string;
  quoteCurrency: string;
  active: boolean;
  createdAt: string;
}

export interface Fee {
  exchange: string;
  takerFee: number;
  updatedAt: string;
}

export interface AppConfig {
  defaultNotional: number;
  freshnessWindowMs: number;
  neutralEpsilonPercent: number;
  fees: Fee[];
  quoteAssets: string[];
}
