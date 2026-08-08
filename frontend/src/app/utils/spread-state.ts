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
  const baseClasses = {
    'p-4': true,
    'rounded-lg': true,
    'border': true,
  };

  switch (state) {
    case 'POTENTIAL':
      return {
        ...baseClasses,
        'bg-green-50': true,
        'border-green-200': true,
      };
    case 'NO_OPPORTUNITY':
      return {
        ...baseClasses,
        'bg-red-50': true,
        'border-red-200': true,
      };
    case 'NEUTRAL':
      return {
        ...baseClasses,
        'bg-gray-50': true,
        'border-gray-200': true,
      };
  }
}

export function getStateBackgroundClass(state: SpreadState): string {
  switch (state) {
    case 'POTENTIAL':
      return 'bg-green-50 dark:bg-green-950';
    case 'NO_OPPORTUNITY':
      return 'bg-red-50 dark:bg-red-950';
    case 'NEUTRAL':
      return 'bg-gray-50 dark:bg-gray-900';
  }
}

export function getStateTextClass(state: SpreadState): string {
  switch (state) {
    case 'POTENTIAL':
      return 'text-green-700 dark:text-green-300';
    case 'NO_OPPORTUNITY':
      return 'text-red-700 dark:text-red-300';
    case 'NEUTRAL':
      return 'text-gray-700 dark:text-gray-300';
  }
}
