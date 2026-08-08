import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { SettingsService } from '../../services/settings.service';
import {
  baseCurrency,
  isUsdtMajor,
  partitionUsdtSymbols,
} from '../../utils/market-groups';

@Component({
  selector: 'app-usdt-market-picker',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './usdt-market-picker.component.html',
})
export class UsdtMarketPickerComponent {
  /** All tracked USDT symbols (from backend). */
  symbols = input<string[]>([]);
  /** Compact bar under header vs full panel in settings. */
  mode = input<'bar' | 'settings'>('bar');

  expanded = signal(false);

  private groups = computed(() => partitionUsdtSymbols(this.symbols()));

  majors = computed(() => this.groups().majors);
  extended = computed(() => this.groups().extended);

  enabledExtended = computed(() =>
    this.extended().filter(s => this.isEnabled(s))
  );

  hiddenExtendedCount = computed(() =>
    this.extended().filter(s => !this.isEnabled(s)).length
  );

  constructor(public settings: SettingsService) {}

  isEnabled(symbol: string): boolean {
    return !this.settings.settings().disabledSymbols.includes(symbol);
  }

  label(symbol: string): string {
    return baseCurrency(symbol);
  }

  isMajor(symbol: string): boolean {
    return isUsdtMajor(symbol);
  }

  toggle(symbol: string) {
    this.settings.toggleSymbol(symbol, !this.isEnabled(symbol));
  }

  showMajorOnly() {
    this.settings.showUsdtMajorOnly(this.symbols());
  }

  showAllUsdt() {
    this.settings.showAllUsdt(this.symbols());
  }

  toggleExpanded() {
    this.expanded.update(v => !v);
  }
}
