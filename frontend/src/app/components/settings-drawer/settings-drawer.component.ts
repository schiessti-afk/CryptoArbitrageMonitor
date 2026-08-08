import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SettingsService, DashboardSettings } from '../../services/settings.service';
import { DatabaseService, DatabaseStats } from '../../services/database.service';
import { ExchangeStatus } from '../../models/spread.model';
import { AppConfig } from '../../models/spread.model';
import { UsdtMarketPickerComponent } from '../usdt-market-picker/usdt-market-picker.component';

@Component({
  selector: 'app-settings-drawer',
  standalone: true,
  imports: [CommonModule, FormsModule, UsdtMarketPickerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './settings-drawer.component.html',
})
export class SettingsDrawerComponent {
  open = input(false);
  closed = output<void>();
  config = input<AppConfig>({
    defaultNotional: 1000,
    freshnessWindowMs: 10000,
    neutralEpsilonPercent: 0.001,
    fees: [],
    quoteAssets: ['USD', 'USDT'],
  });
  exchanges = input<ExchangeStatus[]>([]);
  symbols = input<string[]>([]);

  private triggerButton = viewChild<ElementRef<HTMLButtonElement>>('trigger');
  private previouslyFocused: HTMLElement | null = null;
  private readonly database = inject(DatabaseService);

  readonly dbStats = signal<DatabaseStats | null>(null);
  readonly dbStatsLoading = signal(false);
  readonly dbStatsError = signal<string | null>(null);
  readonly flushConfirming = signal(false);
  readonly flushInProgress = signal(false);
  readonly flushError = signal<string | null>(null);
  readonly flushMessage = signal<string | null>(null);

  constructor(public settings: SettingsService) {
    effect(() => {
      if (this.open()) {
        this.loadDbStats();
      } else {
        this.flushConfirming.set(false);
        this.flushError.set(null);
        this.flushMessage.set(null);
      }
    });
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    if (this.open()) {
      this.close();
    }
  }

  close() {
    this.closed.emit();
    this.previouslyFocused?.focus();
  }

  onOpen(trigger: HTMLButtonElement) {
    this.previouslyFocused = trigger;
  }

  isExchangeEnabled(exchange: string): boolean {
    return !this.settings.settings().disabledExchanges.includes(exchange);
  }

  isSymbolEnabled(symbol: string): boolean {
    return !this.settings.settings().disabledSymbols.includes(symbol);
  }

  symbolsByQuote(quote: string): string[] {
    return this.symbols().filter(s => s.endsWith('/' + quote)).sort();
  }

  usdtSymbols(): string[] {
    return this.symbolsByQuote('USDT');
  }

  quoteAssetsOrdered(): string[] {
    const available = new Set(this.config().quoteAssets);
    return ['USDT', 'USD'].filter(q => available.has(q));
  }

  patch(partial: Partial<DashboardSettings>) {
    this.settings.update(partial);
  }

  reset() {
    this.settings.resetToDefaults();
  }

  loadDbStats() {
    this.dbStatsLoading.set(true);
    this.dbStatsError.set(null);
    this.database.getStats().subscribe({
      next: stats => {
        this.dbStats.set(stats);
        this.dbStatsLoading.set(false);
      },
      error: () => {
        this.dbStatsError.set('Could not load database size.');
        this.dbStatsLoading.set(false);
      },
    });
  }

  requestFlush() {
    this.flushConfirming.set(true);
    this.flushError.set(null);
    this.flushMessage.set(null);
  }

  cancelFlush() {
    this.flushConfirming.set(false);
  }

  confirmFlush() {
    this.flushInProgress.set(true);
    this.flushError.set(null);
    this.flushMessage.set(null);
    this.database.flushSpreadLog().subscribe({
      next: result => {
        this.dbStats.set(result.stats);
        this.flushMessage.set(
          result.deletedRows === 0
            ? 'History was already empty.'
            : `Cleared ${result.deletedRows.toLocaleString()} history row${result.deletedRows === 1 ? '' : 's'}.`
        );
        this.flushConfirming.set(false);
        this.flushInProgress.set(false);
      },
      error: () => {
        this.flushError.set('Failed to clear history.');
        this.flushInProgress.set(false);
      },
    });
  }
}
