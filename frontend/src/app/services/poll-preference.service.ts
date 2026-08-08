import { Injectable, effect, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { SettingsService } from './settings.service';
import { Pair } from '../models/spread.model';

@Injectable({
  providedIn: 'root',
})
export class PollPreferenceService {
  private readonly http = inject(HttpClient);
  private readonly settings = inject(SettingsService);

  /** All active tracked symbols from the backend (not filtered by quote toggle). */
  readonly trackedSymbols = signal<string[]>([]);

  constructor() {
    this.http.get<Pair[]>('/api/pairs').subscribe({
      next: pairs => {
        const symbols = pairs.filter(p => p.active).map(p => p.symbol).sort();
        this.trackedSymbols.set(symbols);
        this.syncNow();
      },
      error: () => {
        // Non-fatal — Coinbase stays on core polling until pairs load.
      },
    });

    effect(() => {
      this.settings.settings();
      this.trackedSymbols();
      this.syncNow();
    });
  }

  enabledSymbolCount(): number {
    const disabled = new Set(this.settings.settings().disabledSymbols);
    return this.trackedSymbols().filter(s => !disabled.has(s)).length;
  }

  private syncNow(): void {
    const symbols = this.trackedSymbols();
    if (symbols.length === 0) {
      return;
    }
    const disabled = new Set(this.settings.settings().disabledSymbols);
    const enabled = symbols.filter(s => !disabled.has(s));
    this.http.put('/api/preferences/poll', { enabledSymbols: enabled }).subscribe({
      error: () => {
        // Non-fatal — backend keeps last known preference or core-only default.
      },
    });
  }
}
