import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { SpreadOpportunity, SymbolCoverage } from '../../models/spread.model';
import { FlashOnChangeDirective } from '../../directives/flash-on-change.directive';
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
import {
  formatCompact,
  liquidityChipClass,
  summarizeLiquidity,
} from '../../utils/liquidity';

@Component({
  selector: 'app-spread-detail',
  standalone: true,
  imports: [CommonModule, FlashOnChangeDirective],
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
  selectedRoute = input<SpreadOpportunity | null>(null);

  clearFilters = output<void>();
  routeSelected = output<SpreadOpportunity>();

  profit(opp: SpreadOpportunity): number {
    return estimatedProfit(this.notional(), opp.netSpreadPercent);
  }

  liquidity(opp: SpreadOpportunity) {
    return summarizeLiquidity(opp, this.notional(), this.quoteAsset());
  }

  liqChipClass(opp: SpreadOpportunity) {
    return liquidityChipClass(this.liquidity(opp).grade);
  }

  formatCompact = formatCompact;

  isSelected(opp: SpreadOpportunity): boolean {
    const selected = this.selectedRoute();
    if (!selected) return false;
    return (
      selected.symbol === opp.symbol &&
      selected.buyExchange === opp.buyExchange &&
      selected.sellExchange === opp.sellExchange
    );
  }

  selectRoute(opp: SpreadOpportunity) {
    this.routeSelected.emit(opp);
  }

  routeKey(opp: SpreadOpportunity): string {
    return `${opp.symbol}|${opp.buyExchange}|${opp.sellExchange}`;
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
