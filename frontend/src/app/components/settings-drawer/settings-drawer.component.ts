import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SettingsService, DashboardSettings } from '../../services/settings.service';
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

  constructor(public settings: SettingsService) {}

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
}
