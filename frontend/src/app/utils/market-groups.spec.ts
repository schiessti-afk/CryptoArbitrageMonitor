import {
  baseCurrency,
  bootstrapEnabledOrder,
  isUsdtExtended,
  isUsdtMajor,
  partitionUsdtSymbols,
  USDT_MAJOR_SYMBOLS,
} from './market-groups';

describe('market-groups', () => {
  it('classifies major vs extended USDT markets', () => {
    expect(isUsdtMajor('BTC/USDT')).toBeTrue();
    expect(isUsdtExtended('BNB/USDT')).toBeTrue();
    expect(isUsdtExtended('BTC/USDT')).toBeFalse();
    expect(baseCurrency('ETH/USD')).toBe('ETH');
  });

  it('partitionUsdtSymbols splits majors and extended', () => {
    const partitioned = partitionUsdtSymbols([
      'BNB/USDT',
      'BTC/USDT',
      'ETH/USD',
      'SOL/USDT',
      'ADA/USDT',
    ]);
    expect(partitioned.majors).toEqual(['BTC/USDT', 'SOL/USDT']);
    expect(partitioned.extended).toEqual(['ADA/USDT', 'BNB/USDT']);
  });

  it('bootstrapEnabledOrder ranks USD then majors then remaining', () => {
    const ordered = bootstrapEnabledOrder([
      'BNB/USDT',
      'ETH/USD',
      'SOL/USDT',
      'BTC/USD',
      'BTC/USDT',
    ]);
    expect(ordered.slice(0, 2)).toEqual(['BTC/USD', 'ETH/USD']);
    expect(ordered.slice(2, 4)).toEqual(['BTC/USDT', 'SOL/USDT']);
    expect(ordered[ordered.length - 1]).toBe('BNB/USDT');
    expect(USDT_MAJOR_SYMBOLS).toContain('DOGE/USDT');
  });
});
