import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'crypto-arbitrage-monitor:quoteAsset';
const DEFAULT_QUOTE_ASSET = 'USD';

/**
 * Holds the selected quote-asset universe (USD or USDT) for the whole dashboard.
 * A single signal shared by the toggle, the card/matrix filters, and the connection-status
 * chips, so switching the toggle updates all three views from the exact same source — no
 * separate state to drift out of sync.
 */
@Injectable({
  providedIn: 'root'
})
export class QuoteAssetService {
  readonly selected = signal<string>(this.readInitial());

  private readInitial(): string {
    try {
      return localStorage.getItem(STORAGE_KEY) ?? DEFAULT_QUOTE_ASSET;
    } catch {
      // localStorage unavailable (private browsing, SSR, etc.) — fall back silently.
      return DEFAULT_QUOTE_ASSET;
    }
  }

  select(quoteAsset: string) {
    this.selected.set(quoteAsset);
    try {
      localStorage.setItem(STORAGE_KEY, quoteAsset);
    } catch {
      // Non-fatal — selection still works for this session.
    }
  }
}
