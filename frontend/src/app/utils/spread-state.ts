/**
 * Spread opportunity state classifier and styling utilities.
 * Provides a unified way to classify spreads and style them consistently across components.
 */

export type SpreadState = 'POTENTIAL' | 'NO_OPPORTUNITY' | 'NEUTRAL';

export const NEUTRAL_EPSILON_PERCENT = 0.001;  // 0.001% threshold

export function getSpreadState(netPercent: number): SpreadState {
  if (netPercent > NEUTRAL_EPSILON_PERCENT) {
    return 'POTENTIAL';
  } else if (netPercent < -NEUTRAL_EPSILON_PERCENT) {
    return 'NO_OPPORTUNITY';
  } else {
    return 'NEUTRAL';
  }
}

export function getStateLabel(state: SpreadState, netPercent: number): string {
  const formatted = Math.abs(netPercent).toFixed(4);

  switch (state) {
    case 'POTENTIAL':
      return `POTENTIAL OPPORTUNITY — Net spread: +${formatted}%`;
    case 'NO_OPPORTUNITY':
      return `NO POSITIVE OPPORTUNITY — Best net spread: ${netPercent.toFixed(4)}%`;
    case 'NEUTRAL':
      return `NO MEANINGFUL SPREAD — Net spread: ${netPercent.toFixed(4)}%`;
  }
}

export function getIndicatorEmoji(state: SpreadState): string {
  switch (state) {
    case 'POTENTIAL':
      return '🟢';
    case 'NO_OPPORTUNITY':
      return '🔴';
    case 'NEUTRAL':
      return '⚪';
  }
}

export function getStateClasses(state: SpreadState): Record<string, boolean> {
  switch (state) {
    case 'POTENTIAL':
      return { 'row-positive': true, 'data-row': true };
    case 'NO_OPPORTUNITY':
      return { 'row-negative': true, 'data-row': true };
    case 'NEUTRAL':
      return { 'row-neutral': true, 'data-row': true };
  }
}

export function getStateBackgroundClass(state: SpreadState): string {
  switch (state) {
    case 'POTENTIAL':
      return 'row-positive';
    case 'NO_OPPORTUNITY':
      return 'row-negative';
    case 'NEUTRAL':
      return 'row-neutral';
  }
}

export function getStateTextClass(state: SpreadState): string {
  switch (state) {
    case 'POTENTIAL':
      return 'text-positive';
    case 'NO_OPPORTUNITY':
      return 'text-negative';
    case 'NEUTRAL':
      return 'text-muted';
  }
}
