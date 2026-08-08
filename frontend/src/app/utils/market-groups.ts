/** The five default USDT markets shown on first load. */
export const USDT_MAJOR_SYMBOLS = [
  'BTC/USDT',
  'ETH/USDT',
  'SOL/USDT',
  'XRP/USDT',
  'DOGE/USDT',
] as const;

/** Extended USDT markets hidden by default (BNB + Sprint expansion). */
export const USDT_EXTENDED_SYMBOLS = [
  'BNB/USDT',
  'ADA/USDT',
  'AVAX/USDT',
  'LINK/USDT',
  'SUI/USDT',
  'DOT/USDT',
  'TON/USDT',
  'LTC/USDT',
  'BCH/USDT',
  'SHIB/USDT',
  'PEPE/USDT',
  'UNI/USDT',
  'NEAR/USDT',
  'APT/USDT',
  'ATOM/USDT',
  'FIL/USDT',
  'ARB/USDT',
  'OP/USDT',
  'INJ/USDT',
  'AAVE/USDT',
  'WIF/USDT',
] as const;

export const DEFAULT_DISABLED_USDT_EXTENDED = [...USDT_EXTENDED_SYMBOLS];

export function baseCurrency(symbol: string): string {
  return symbol.split('/')[0];
}

export function isUsdtMajor(symbol: string): boolean {
  return (USDT_MAJOR_SYMBOLS as readonly string[]).includes(symbol);
}

export function isUsdtExtended(symbol: string): boolean {
  return symbol.endsWith('/USDT') && !isUsdtMajor(symbol);
}

export function partitionUsdtSymbols(symbols: string[]): {
  majors: string[];
  extended: string[];
} {
  const usdt = symbols.filter(s => s.endsWith('/USDT')).sort();
  return {
    majors: usdt.filter(isUsdtMajor),
    extended: usdt.filter(isUsdtExtended),
  };
}
