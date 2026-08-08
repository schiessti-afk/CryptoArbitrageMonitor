export interface SpreadOpportunity {
  symbol: string;
  buyExchange: string;
  sellExchange: string;
  buyPrice: number;
  sellPrice: number;
  rawSpreadPercent: number;
  netSpreadPercent: number;
  calculatedAt?: string;
}

export interface ExchangeStatus {
  exchange: string;
  available: boolean;
  lastUpdate?: string;
  freshness: 'FRESH' | 'STALE' | 'NEVER';
}

export interface SpreadSnapshot {
  calculatedAt: string;
  matrix: SpreadOpportunity[];
  bestPerSymbol: SpreadOpportunity[];
  exchanges: ExchangeStatus[];
  freshExchangeCount: number;
  live: boolean;
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
