import { Component, Input, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SpreadOpportunity } from '../../models/spread.model';
import { getStateClasses, getSpreadState } from '../../utils/spread-state';

interface MatrixGroup {
  symbol: string;
  rows: SpreadOpportunity[];
}

@Component({
  selector: 'app-spread-table',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './spread-table.component.html',
  styles: []
})
export class SpreadTableComponent {
  @Input() matrix: SpreadOpportunity[] = [];
  @Input() notional = 1000;

  groupedMatrix = computed(() => {
    const groups = new Map<string, SpreadOpportunity[]>();

    for (const row of this.matrix) {
      if (!groups.has(row.symbol)) {
        groups.set(row.symbol, []);
      }
      groups.get(row.symbol)!.push(row);
    }

    return Array.from(groups.entries())
      .map(([symbol, rows]) => ({
        symbol,
        rows: rows.sort((a, b) => {
          // Sort by net spread desc, then raw spread desc, then buy exchange name
          if (b.netSpreadPercent !== a.netSpreadPercent) {
            return b.netSpreadPercent - a.netSpreadPercent;
          }
          if (b.rawSpreadPercent !== a.rawSpreadPercent) {
            return b.rawSpreadPercent - a.rawSpreadPercent;
          }
          return a.buyExchange.localeCompare(b.buyExchange);
        })
      }))
      .sort((a, b) => a.symbol.localeCompare(b.symbol));
  });

  getRowClasses(netPercent: number) {
    const state = getSpreadState(netPercent);
    const classes = getStateClasses(state);
    // Override base padding for table rows
    return {
      ...classes,
      'p-4': false,
      'border': true,
    };
  }

  getStateBackgroundClass(netPercent: number): string {
    if (netPercent > 0.001) return 'bg-green-50';
    if (netPercent < -0.001) return 'bg-red-50';
    return '';
  }

  calculateFeeImpact(opp: SpreadOpportunity): number {
    return opp.rawSpreadPercent - opp.netSpreadPercent;
  }
}
