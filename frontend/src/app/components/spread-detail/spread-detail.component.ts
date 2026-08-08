import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { SpreadOpportunity, SymbolCoverage } from '../../models/spread.model';
import {
  getSpreadState,
  getStateClasses,
  getStateLabel,
  getIndicatorEmoji,
  getStateTextClass,
} from '../../utils/spread-state';
import {
  estimatedProfit,
  formatProfit,
  formatSignedPercent,
  priceDecimals,
} from '../../utils/format-numbers';

@Component({
  selector: 'app-spread-detail',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './spread-detail.component.html',
})
export class SpreadDetailComponent {
  opportunities = input<SpreadOpportunity[]>([]);
  notional = input(1000);
  quoteAsset = input('USD');
  coverage = input<SymbolCoverage[]>([]);
  loading = input(false);
  filteredEmpty = input(false);
  density = input<'comfortable' | 'compact'>('comfortable');

  clearFilters = output<void>();

  profit(opp: SpreadOpportunity): number {
    return estimatedProfit(this.notional(), opp.netSpreadPercent);
  }

  getState(netPercent: number) {
    return getSpreadState(netPercent);
  }

  getClasses(netPercent: number) {
    return getStateClasses(this.getState(netPercent));
  }

  getLabel(netPercent: number) {
    return getStateLabel(this.getState(netPercent), netPercent);
  }

  getEmoji(netPercent: number) {
    return getIndicatorEmoji(this.getState(netPercent));
  }

  getTextClass(netPercent: number) {
    return getStateTextClass(this.getState(netPercent));
  }

  formatPercent(value: number) {
    return formatSignedPercent(value);
  }

  formatPrice(value: number) {
    return value;
  }

  priceFormat(value: number) {
    return priceDecimals(value);
  }

  formatProfitValue(opp: SpreadOpportunity) {
    return formatProfit(this.profit(opp), this.quoteAsset());
  }

  coverageMessage(symbol: string): string | null {
    const cov = this.coverage().find(c => c.symbol === symbol);
    if (!cov || cov.freshVenues >= 2) return null;
    if (cov.freshVenues === 0) {
      return `No venues reporting ${symbol} — waiting for data.`;
    }
    return `Only ${cov.freshVenues} venue reporting ${symbol} — a cross-venue comparison needs 2.`;
  }

  rowPadding(): string {
    return this.density() === 'compact' ? 'py-2 px-3' : 'py-3 px-4';
  }
}
