import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SpreadOpportunity, SymbolCoverage } from '../../models/spread.model';
import { DashboardSettings } from '../../services/settings.service';
import { getSpreadState, getStateBackgroundClass } from '../../utils/spread-state';
import { isBelowThreshold } from '../../utils/dashboard-filter';
import { formatSignedPercent, priceDecimals } from '../../utils/format-numbers';

@Component({
  selector: 'app-spread-table',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './spread-table.component.html',
})
export class SpreadTableComponent {
  matrix = input<SpreadOpportunity[]>([]);
  notional = input<number>(1000);
  quoteAsset = input('USD');
  coverage = input<SymbolCoverage[]>([]);
  loading = input(false);
  filteredEmpty = input(false);
  settings = input<DashboardSettings>({
    version: 1,
    disabledExchanges: [],
    disabledSymbols: [],
    minNetSpreadPercent: 0,
    hideBelowThreshold: false,
    theme: 'system',
    density: 'comfortable',
    freshnessWindowMsOverride: null,
    defaultNotionalOverride: null,
  });
  density = input<'comfortable' | 'compact'>('comfortable');

  clearFilters = output<void>();

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

  cellPadding = computed(() => (this.density() === 'compact' ? 'p-2 text-xs' : 'p-3 text-sm'));

  getRowClass(netPercent: number): string {
    const state = getSpreadState(netPercent);
    const dim =
      !this.settings().hideBelowThreshold &&
      isBelowThreshold({ netSpreadPercent: netPercent } as SpreadOpportunity, this.settings());
    return `${getStateBackgroundClass(state)}${dim ? ' opacity-50' : ''}`;
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
}
