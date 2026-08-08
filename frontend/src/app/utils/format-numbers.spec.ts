import {
  estimatedProfit,
  formatProfit,
  formatQuoteAmount,
  formatSignedPercent,
  priceDecimals,
} from './format-numbers';

describe('format-numbers', () => {
  it('priceDecimals picks magnitude-aware digit ranges', () => {
    expect(priceDecimals(1500)).toBe('1.2-2');
    expect(priceDecimals(1.5)).toBe('1.2-4');
    expect(priceDecimals(0.05)).toBe('1.4-6');
    expect(priceDecimals(0.0001)).toBe('1.6-8');
  });

  it('formatSignedPercent includes plus for positive values', () => {
    expect(formatSignedPercent(1.2345)).toBe('+1.2345%');
    expect(formatSignedPercent(-0.5, 2)).toBe('-0.50%');
    expect(formatSignedPercent(0, 2)).toBe('0.00%');
  });

  it('formats quote amounts and profits', () => {
    expect(formatQuoteAmount(12.345, 'USDT')).toBe('12.35 USDT');
    expect(formatProfit(10.5, 'USD')).toBe('10.50 USD');
    expect(formatProfit(-3.2, 'USD')).toBe('-3.20 USD');
  });

  it('estimatedProfit scales notional by net percent', () => {
    expect(estimatedProfit(1000, 0.5)).toBe(5);
    expect(estimatedProfit(250, -1)).toBe(-2.5);
  });
});
