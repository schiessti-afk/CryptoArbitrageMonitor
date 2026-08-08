import { Component, signal, effect, OnInit, OnDestroy, NgZone } from '@angular/core';
import { CommonModule } from '@angular/common';
import { WebsocketService } from '../../services/websocket.service';
import { QuoteAssetService } from '../../services/quote-asset.service';

type ConnectionBadge = 'LIVE' | 'DEGRADED' | 'STALE';

@Component({
  selector: 'app-connection-status',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './connection-status.component.html',
  styles: []
})
export class ConnectionStatusComponent implements OnInit, OnDestroy {
  now = signal(Date.now());
  lastMessageAge = signal('—');
  badge = signal<ConnectionBadge>('STALE');

  private tickerInterval: any;

  constructor(
    public websocket: WebsocketService,
    public quoteAsset: QuoteAssetService,
    private ngZone: NgZone
  ) {
    effect(() => {
      const snap = this.websocket.snapshot();
      const currentNow = this.now();
      const selectedQuote = this.quoteAsset.selected();

      if (!snap) {
        this.lastMessageAge.set('—');
        this.badge.set('STALE');
        return;
      }

      const receivedAtMs = new Date(snap.calculatedAt).getTime();
      const ageSeconds = (currentNow - receivedAtMs) / 1000;

      // liveByQuote reflects only the venues relevant to the selected quote asset — a USD outage
      // must not read as DEGRADED while looking at USDT, and vice versa.
      const liveForSelectedQuote = snap.liveByQuote?.[selectedQuote] ?? snap.live;

      if (ageSeconds > 10) {
        this.lastMessageAge.set(`Updated ${Math.floor(ageSeconds)}s ago`);
        this.badge.set('STALE');
      } else if (!liveForSelectedQuote) {
        this.lastMessageAge.set(`Updated ${Math.floor(ageSeconds)}s ago`);
        this.badge.set('DEGRADED');
      } else {
        this.lastMessageAge.set(`Updated ${Math.floor(ageSeconds)}s ago`);
        this.badge.set('LIVE');
      }
    });
  }

  ngOnInit() {
    this.ngZone.runOutsideAngular(() => {
      this.tickerInterval = setInterval(() => {
        this.ngZone.run(() => {
          this.now.set(Date.now());
        });
      }, 1000);
    });
  }

  ngOnDestroy() {
    if (this.tickerInterval) {
      clearInterval(this.tickerInterval);
    }
  }

  snapshot() {
    return this.websocket.snapshot();
  }

  // Only venues that actually list the selected quote asset are shown — a venue that simply
  // doesn't offer USD (e.g. Bitget, KuCoin) must never render as if it had failed to report one.
  visibleExchanges() {
    const selected = this.quoteAsset.selected();
    return (this.snapshot()?.exchanges ?? []).filter(ex =>
      ex.offeredQuoteAssets?.includes(selected)
    );
  }

  getExchangeClass(freshness: string): string {
    switch (freshness) {
      case 'FRESH':
        return 'bg-green-100 text-green-800';
      case 'STALE':
        return 'bg-yellow-100 text-yellow-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  }

  getBadgeEmoji(): string {
    switch (this.badge()) {
      case 'LIVE':
        return '🟢';
      case 'DEGRADED':
        return '🟡';
      case 'STALE':
        return '🔴';
    }
  }
}
