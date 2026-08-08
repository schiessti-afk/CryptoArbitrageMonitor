/** Magnitude-aware price formatting for streaming dashboards. */
export function priceDecimals(value: number): string {
  const abs = Math.abs(value);
  if (abs >= 1000) return '1.2-2';
  if (abs >= 1) return '1.2-4';
  if (abs >= 0.01) return '1.4-6';
  return '1.6-8';
}

export function formatSignedPercent(value: number, digits = 4): string {
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toFixed(digits)}%`;
}

export function formatQuoteAmount(value: number, quoteAsset: string, digits = 2): string {
  return `${value.toFixed(digits)} ${quoteAsset}`;
}

export function formatProfit(value: number, quoteAsset: string): string {
  const sign = value >= 0 ? '' : '-';
  return `${sign}${Math.abs(value).toFixed(2)} ${quoteAsset}`;
}

export function estimatedProfit(notional: number, netSpreadPercent: number): number {
  return (notional * netSpreadPercent) / 100;
}
