import {
  ChangeDetectionStrategy,
  Component,
  effect,
  input,
  OnDestroy,
  OnInit,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { WebsocketService } from '../../services/websocket.service';
import { QuoteAssetService } from '../../services/quote-asset.service';
import { SettingsService } from '../../services/settings.service';
import { computeVisibleLive } from '../../utils/dashboard-filter';

type ConnectionBadge = 'LIVE' | 'DEGRADED' | 'STALE';

@Component({
  selector: 'app-connection-status',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './connection-status.component.html',
})
export class ConnectionStatusComponent implements OnInit, OnDestroy {
  freshnessWindowMs = input(10000);
  nowMs = input(Date.now());

  lastMessageAge = signal('—');
  badge = signal<ConnectionBadge>('STALE');

  constructor(
    public websocket: WebsocketService,
    public quoteAsset: QuoteAssetService,
    public settings: SettingsService,
  ) {
    effect(() => {
      const snap = this.websocket.snapshot();
      const currentNow = this.nowMs();
      const selectedQuote = this.quoteAsset.selected();
      const settings = this.settings.settings();
      const freshnessWindow = this.freshnessWindowMs();

      if (!snap) {
        this.lastMessageAge.set('—');
        this.badge.set('STALE');
        return;
      }

      const receivedAtMs = new Date(snap.calculatedAt).getTime();
      const ageSeconds = (currentNow - receivedAtMs) / 1000;
      const ageLabel = `Updated ${Math.floor(ageSeconds)}s ago`;
      this.lastMessageAge.set(ageLabel);

      const fallbackLive = snap.liveByQuote?.[selectedQuote] ?? snap.live;
      const liveForSelectedQuote = computeVisibleLive(
        snap.exchanges ?? [],
        selectedQuote,
        settings,
        fallbackLive,
        freshnessWindow,
        currentNow
      );

      if (ageSeconds > freshnessWindow / 1000) {
        this.badge.set('STALE');
      } else if (!liveForSelectedQuote) {
        this.badge.set('DEGRADED');
      } else {
        this.badge.set('LIVE');
      }
    });
  }

  ngOnInit() {}

  ngOnDestroy() {}

  snapshot() {
    return this.websocket.snapshot();
  }

  visibleExchanges() {
    const selected = this.quoteAsset.selected();
    const disabled = new Set(this.settings.settings().disabledExchanges);
    return (this.snapshot()?.exchanges ?? []).filter(ex =>
      ex.offeredQuoteAssets?.includes(selected)
    );
  }

  hiddenExchanges() {
    const selected = this.quoteAsset.selected();
    const disabled = new Set(this.settings.settings().disabledExchanges);
    return (this.snapshot()?.exchanges ?? []).filter(ex =>
      ex.offeredQuoteAssets?.includes(selected) && disabled.has(ex.exchange)
    );
  }

  getExchangeClass(freshness: string): string {
    switch (freshness) {
      case 'FRESH':
        return 'chip chip-fresh';
      case 'STALE':
        return 'chip chip-stale';
      default:
        return 'chip chip-never';
    }
  }

}
