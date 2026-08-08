import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { WebsocketService } from '../../services/websocket.service';
import { QuoteAssetService } from '../../services/quote-asset.service';
import { SettingsService } from '../../services/settings.service';
import { PollPreferenceService } from '../../services/poll-preference.service';
import { SpreadDetailComponent } from '../spread-detail/spread-detail.component';
import { SpreadTableComponent } from '../spread-table/spread-table.component';
import { ConnectionStatusComponent } from '../connection-status/connection-status.component';
import { SettingsDrawerComponent } from '../settings-drawer/settings-drawer.component';
import { UsdtMarketPickerComponent } from '../usdt-market-picker/usdt-market-picker.component';
import { OrderBookDrawerComponent } from '../order-book-drawer/order-book-drawer.component';
import { AppConfig, SpreadOpportunity } from '../../models/spread.model';
import {
  OpportunityQuickFilter,
  buildRankedOpportunities,
  collapseMirroredRoutes,
  countVisibleFreshExchanges,
  describeFilterChips,
  filterMatrixBySettings,
  getCoverageForQuote,
  matchesQuoteAsset,
} from '../../utils/dashboard-filter';

const DEFAULT_CONFIG: AppConfig = {
  defaultNotional: 1000,
  freshnessWindowMs: 10000,
  neutralEpsilonPercent: 0.001,
  fees: [],
  quoteAssets: ['USD', 'USDT'],
};

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    SpreadDetailComponent,
    SpreadTableComponent,
    ConnectionStatusComponent,
    SettingsDrawerComponent,
    UsdtMarketPickerComponent,
    OrderBookDrawerComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dashboard.component.html',
})
export class DashboardComponent implements OnInit, OnDestroy {
  notional = signal(1000);
  config = signal<AppConfig>(DEFAULT_CONFIG);
  settingsOpen = signal(false);
  depthOpen = signal(false);
  selectedRoute = signal<SpreadOpportunity | null>(null);
  quickFilter = signal<OpportunityQuickFilter>('all');
  showBothDirections = signal(false);
  now = signal(Date.now());

  private tickerInterval: ReturnType<typeof setInterval> | undefined;

  private quoteFilteredMatrix = computed(() => {
    const snap = this.websocket.snapshot();
    const quote = this.quoteAsset.selected();
    return (snap?.matrix ?? []).filter(o => matchesQuoteAsset(o.symbol, quote));
  });

  private quoteFilteredBest = computed(() => {
    const snap = this.websocket.snapshot();
    const quote = this.quoteAsset.selected();
    return (snap?.bestPerSymbol ?? []).filter(o => matchesQuoteAsset(o.symbol, quote));
  });

  visibleMatrix = computed(() => {
    const settings = this.settings.settings();
    return filterMatrixBySettings(this.quoteFilteredMatrix(), settings);
  });

  displayMatrix = computed(() => {
    const rows = this.visibleMatrix();
    return this.showBothDirections() ? rows : collapseMirroredRoutes(rows);
  });

  rankedOpportunities = computed(() =>
    buildRankedOpportunities(
      this.quoteFilteredBest(),
      this.visibleMatrix(),
      this.settings.settings(),
      this.quickFilter()
    )
  );

  filterChips = computed(() => describeFilterChips(this.settings.settings()));

  totalRoutes = computed(() => this.quoteFilteredMatrix().length);
  visibleRouteCount = computed(() => this.visibleMatrix().length);

  kpiBestSpread = computed(() => {
    const opps = this.rankedOpportunities();
    if (opps.length === 0) return null;
    return opps[0].netSpreadPercent;
  });

  kpiPositiveRoutes = computed(() =>
    this.visibleMatrix().filter(r => r.netSpreadPercent > 0.001).length
  );

  kpiVenueCounts = computed(() => {
    const snap = this.websocket.snapshot();
    const freshnessWindow =
      this.settings.settings().freshnessWindowMsOverride ?? this.config().freshnessWindowMs;
    return countVisibleFreshExchanges(
      snap?.exchanges ?? [],
      this.quoteAsset.selected(),
      this.settings.settings(),
      freshnessWindow,
      this.now()
    );
  });

  kpiSymbolsCovered = computed(() => {
    const coverage = getCoverageForQuote(
      this.websocket.snapshot()?.coverage,
      this.quoteAsset.selected()
    );
    return {
      withSpread: this.rankedOpportunities().length,
      tracked: coverage.length,
      thin: coverage.filter(c => c.freshVenues < 2).length,
    };
  });

  trackedSymbols = computed(() => {
    const coverage = this.websocket.snapshot()?.coverage ?? [];
    return coverage.map(c => c.symbol).sort();
  });

  usdtSymbols = computed(() => {
    const fromPairs = this.pollPreferences.trackedSymbols();
    const source = fromPairs.length ? fromPairs : this.trackedSymbols();
    return source.filter(s => s.endsWith('/USDT')).sort();
  });

  coverageForQuote = computed(() =>
    getCoverageForQuote(this.websocket.snapshot()?.coverage, this.quoteAsset.selected())
  );

  tooFewVenues = computed(() => this.kpiVenueCounts().fresh < 2);

  allSymbolsHidden = computed(() => {
    const settings = this.settings.settings();
    const symbols = this.trackedSymbols().filter(s =>
      matchesQuoteAsset(s, this.quoteAsset.selected())
    );
    return symbols.length > 0 && symbols.every(s => settings.disabledSymbols.includes(s));
  });

  hasActiveFilters = computed(() => this.filterChips().length > 0);

  freshnessWindowMs = computed(
    () => this.settings.settings().freshnessWindowMsOverride ?? this.config().freshnessWindowMs
  );

  quoteAssetsOrdered = computed(() => {
    const available = new Set(this.config().quoteAssets);
    return ['USDT', 'USD'].filter(q => available.has(q));
  });

  quickSelectAmounts = [100, 1000, 5000, 10000, 50000];

  constructor(
    public websocket: WebsocketService,
    public quoteAsset: QuoteAssetService,
    public settings: SettingsService,
    public pollPreferences: PollPreferenceService,
    private http: HttpClient
  ) {}

  ngOnInit() {
    this.http.get<AppConfig>('/api/config').subscribe({
      next: cfg => {
        this.config.set(cfg);
        const override = this.settings.settings().defaultNotionalOverride;
        this.notional.set(override ?? cfg.defaultNotional ?? 1000);
      },
      error: () => this.config.set(DEFAULT_CONFIG),
    });
    this.websocket.connect();
    this.tickerInterval = setInterval(() => this.now.set(Date.now()), 1000);
  }

  ngOnDestroy() {
    this.websocket.disconnect();
    if (this.tickerInterval) clearInterval(this.tickerInterval);
  }

  setNotional(amount: number) {
    this.notional.set(amount);
  }

  selectQuoteAsset(quoteAsset: string) {
    this.quoteAsset.select(quoteAsset);
  }

  openSettings(trigger: HTMLButtonElement) {
    this.depthOpen.set(false);
    this.settingsOpen.set(true);
    setTimeout(() => trigger.focus(), 0);
  }

  closeSettings() {
    this.settingsOpen.set(false);
  }

  openDepth(route: SpreadOpportunity) {
    this.settingsOpen.set(false);
    this.selectedRoute.set(route);
    this.depthOpen.set(true);
  }

  closeDepth() {
    this.depthOpen.set(false);
  }

  setQuickFilter(filter: OpportunityQuickFilter) {
    this.quickFilter.set(filter);
  }
}
