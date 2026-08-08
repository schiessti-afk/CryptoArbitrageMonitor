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
  'TRX/USDT',
  'POL/USDT',
  'ETC/USDT',
  'ALGO/USDT',
  'VET/USDT',
  'ICP/USDT',
  'HBAR/USDT',
  'SEI/USDT',
  'TIA/USDT',
  'STX/USDT',
  'RUNE/USDT',
  'JUP/USDT',
  'WLD/USDT',
  'FET/USDT',
  'RENDER/USDT',
  'TAO/USDT',
  'ENA/USDT',
  'ONDO/USDT',
  'PENDLE/USDT',
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

/** Default enable order: USD pairs, USDT majors, then remaining enabled alphabetically. */
export function bootstrapEnabledOrder(enabledSymbols: string[]): string[] {
  const enabledSet = new Set(enabledSymbols);
  const result: string[] = [];

  for (const symbol of enabledSymbols.filter(s => s.endsWith('/USD')).sort()) {
    result.push(symbol);
  }
  for (const major of USDT_MAJOR_SYMBOLS) {
    if (enabledSet.has(major)) {
      result.push(major);
    }
  }
  const placed = new Set(result);
  for (const symbol of enabledSymbols.filter(s => !placed.has(s)).sort()) {
    result.push(symbol);
  }
  return result;
}
