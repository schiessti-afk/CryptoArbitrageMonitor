import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SpreadOpportunity, SymbolCoverage } from '../../models/spread.model';
import { DashboardSettings } from '../../services/settings.service';
import { FlashOnChangeDirective } from '../../directives/flash-on-change.directive';
import { getSpreadState, getStateBackgroundClass } from '../../utils/spread-state';
import { isBelowThreshold } from '../../utils/dashboard-filter';
import { formatSignedPercent, priceDecimals } from '../../utils/format-numbers';
import {
  formatCompact,
  liquidityChipClass,
  summarizeLiquidity,
} from '../../utils/liquidity';

@Component({
  selector: 'app-spread-table',
  standalone: true,
  imports: [CommonModule, FlashOnChangeDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './spread-table.component.html',
})
export class SpreadTableComponent {
  matrix = input<SpreadOpportunity[]>([]);
  notional = input<number>(1000);
  quoteAsset = input('USD');
  coverage = input<SymbolCoverage[]>([]);
  loading = input(false);
  settings = input<DashboardSettings>({
    version: 2,
    disabledExchanges: [],
    disabledSymbols: [],
    minNetSpreadPercent: 0,
    hideBelowThreshold: false,
    theme: 'dark',
    density: 'comfortable',
    freshnessWindowMsOverride: null,
    defaultNotionalOverride: null,
  });
  density = input<'comfortable' | 'compact'>('comfortable');
  selectedRoute = input<SpreadOpportunity | null>(null);

  routeSelected = output<SpreadOpportunity>();

  /** Symbol groups expanded in the matrix accordion — collapsed by default. */
  private expandedSymbols = signal<Set<string>>(new Set());

  groupedMatrix = computed(() => {
    const groups = new Map<string, SpreadOpportunity[]>();

    for (const row of this.matrix()) {
      if (!groups.has(row.symbol)) {
        groups.set(row.symbol, []);
      }
      groups.get(row.symbol)!.push(row);
    }

    return Array.from(groups.entries())
      .map(([symbol, rows]) => ({
        symbol,
        rows: rows.sort((a, b) => {
          if (b.netSpreadPercent !== a.netSpreadPercent) {
            return b.netSpreadPercent - a.netSpreadPercent;
          }
          if (b.rawSpreadPercent !== a.rawSpreadPercent) {
            return b.rawSpreadPercent - a.rawSpreadPercent;
          }
          return a.buyExchange.localeCompare(b.buyExchange);
        }),
      }))
      .sort((a, b) => a.symbol.localeCompare(b.symbol));
  });

  allCollapsed = computed(() => this.expandedSymbols().size === 0);

  cellPadding = computed(() => (this.density() === 'compact' ? 'p-2 text-xs' : 'p-3 text-sm'));

  isExpanded(symbol: string): boolean {
    return this.expandedSymbols().has(symbol);
  }

  toggleSymbol(symbol: string): void {
    this.expandedSymbols.update(current => {
      const next = new Set(current);
      if (next.has(symbol)) {
        next.delete(symbol);
      } else {
        next.add(symbol);
      }
      return next;
    });
  }

  expandAll(): void {
    this.expandedSymbols.set(new Set(this.groupedMatrix().map(g => g.symbol)));
  }

  collapseAll(): void {
    this.expandedSymbols.set(new Set());
  }

  bestNetPercent(rows: SpreadOpportunity[]): number | null {
    if (rows.length === 0) return null;
    return rows[0].netSpreadPercent;
  }

  getRowClass(netPercent: number): string {
    const state = getSpreadState(netPercent);
    const dim =
      !this.settings().hideBelowThreshold &&
      isBelowThreshold({ netSpreadPercent: netPercent } as SpreadOpportunity, this.settings());
    return `data-row ${getStateBackgroundClass(state)}${dim ? ' opacity-50' : ''}`;
  }

  calculateFeeImpact(opp: SpreadOpportunity): number {
    return opp.rawSpreadPercent - opp.netSpreadPercent;
  }

  priceFormat(value: number) {
    return priceDecimals(value);
  }

  formatNet(value: number) {
    return formatSignedPercent(value);
  }

  coverageMessage(symbol: string): string | null {
    const cov = this.coverage().find(c => c.symbol === symbol);
    if (!cov || cov.freshVenues >= 2) return null;
    if (cov.freshVenues === 0) return `No venues reporting — cannot compute ${symbol} spreads.`;
    return `Only ${cov.freshVenues} venue reporting — needs 2 for ${symbol}.`;
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

  selectRoute(opp: SpreadOpportunity, event: Event) {
    event.stopPropagation();
    this.routeSelected.emit(opp);
  }

  routeKey(opp: SpreadOpportunity): string {
    return `${opp.symbol}|${opp.buyExchange}|${opp.sellExchange}`;
  }
}
