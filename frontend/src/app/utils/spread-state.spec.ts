import {
  getIndicatorEmoji,
  getSpreadState,
  getStateBackgroundClass,
  getStateClasses,
  getStateLabel,
  getStateTextClass,
  NEUTRAL_EPSILON_PERCENT,
} from './spread-state';

describe('spread-state', () => {
  it('classifies around the neutral epsilon', () => {
    expect(getSpreadState(NEUTRAL_EPSILON_PERCENT + 0.001)).toBe('POTENTIAL');
    expect(getSpreadState(-(NEUTRAL_EPSILON_PERCENT + 0.001))).toBe('NO_OPPORTUNITY');
    expect(getSpreadState(0)).toBe('NEUTRAL');
    expect(getSpreadState(NEUTRAL_EPSILON_PERCENT)).toBe('NEUTRAL');
  });

  it('builds labels for each state', () => {
    expect(getStateLabel('POTENTIAL', 0.1234)).toContain('POTENTIAL OPPORTUNITY');
    expect(getStateLabel('NO_OPPORTUNITY', -0.25)).toContain('NO POSITIVE OPPORTUNITY');
    expect(getStateLabel('NEUTRAL', 0)).toContain('NO MEANINGFUL SPREAD');
  });

  it('maps styling helpers consistently', () => {
    expect(getIndicatorEmoji('POTENTIAL')).toBe('🟢');
    expect(getStateBackgroundClass('NO_OPPORTUNITY')).toBe('row-negative');
    expect(getStateTextClass('NEUTRAL')).toBe('text-muted');
    expect(getStateClasses('POTENTIAL')).toEqual({ 'row-positive': true, 'data-row': true });
  });
});
