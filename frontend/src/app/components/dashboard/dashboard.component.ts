import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { WebsocketService } from '../../services/websocket.service';
import { QuoteAssetService } from '../../services/quote-asset.service';
import { SpreadDetailComponent } from '../spread-detail/spread-detail.component';
import { SpreadTableComponent } from '../spread-table/spread-table.component';
import { ConnectionStatusComponent } from '../connection-status/connection-status.component';
import { AppConfig } from '../../models/spread.model';

const DEFAULT_CONFIG: AppConfig = {
  defaultNotional: 1000,
  freshnessWindowMs: 10000,
  neutralEpsilonPercent: 0.001,
  fees: [],
  quoteAssets: ['USD', 'USDT']
};

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    SpreadDetailComponent,
    SpreadTableComponent,
    ConnectionStatusComponent
  ],
  templateUrl: './dashboard.component.html',
  styles: []
})
export class DashboardComponent implements OnInit, OnDestroy {
  notional = signal(1000);
  config = signal<AppConfig>(DEFAULT_CONFIG);

  // A symbol's quote asset is its suffix ("BTC/USDT" -> "USDT"); filtering this way needs no
  // extra round-trip and matches exactly what the backend uses to group tracked pairs.
  private matchesSelectedQuote = (symbol: string) =>
    symbol.endsWith('/' + this.quoteAsset.selected());

  opportunities = computed(() =>
    (this.websocket.snapshot()?.bestPerSymbol ?? []).filter(o => this.matchesSelectedQuote(o.symbol))
  );
  matrix = computed(() =>
    (this.websocket.snapshot()?.matrix ?? []).filter(o => this.matchesSelectedQuote(o.symbol))
  );

  // Data-driven venue list for the header tooltip — built from whatever native symbols are
  // actually present in the current (filtered) matrix, so it never hardcodes a market string
  // that would be wrong under the other quote asset.
  venueSummary = computed(() => {
    const seen = new Map<string, string>();
    for (const row of this.matrix()) {
      if (row.buyNativeSymbol) seen.set(row.buyExchange, row.buyNativeSymbol);
      if (row.sellNativeSymbol) seen.set(row.sellExchange, row.sellNativeSymbol);
    }
    return Array.from(seen.entries())
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([exchange, native]) => `${exchange} ${native}`)
      .join(', ');
  });

  quickSelectAmounts = [100, 1000, 5000, 10000, 50000];

  constructor(
    public websocket: WebsocketService,
    public quoteAsset: QuoteAssetService,
    private http: HttpClient
  ) {}

  ngOnInit() {
    this.http.get<AppConfig>('/api/config').subscribe({
      next: cfg => {
        this.config.set(cfg);
        if (cfg.defaultNotional) {
          this.notional.set(cfg.defaultNotional);
        }
      },
      error: error => {
        console.warn('Failed to fetch config, using defaults:', error);
        this.config.set(DEFAULT_CONFIG);
      }
    });
    this.websocket.connect();
  }

  ngOnDestroy() {
    this.websocket.disconnect();
  }

  setNotional(amount: number) {
    this.notional.set(amount);
  }

  selectQuoteAsset(quoteAsset: string) {
    this.quoteAsset.select(quoteAsset);
  }
}
