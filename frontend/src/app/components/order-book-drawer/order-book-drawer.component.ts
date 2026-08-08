import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { SpreadOpportunity } from '../../models/spread.model';
import {
  OrderBookService,
  RouteOrderBookResponse,
} from '../../services/order-book.service';
import {
  buildDepthLevels,
  findFillIndex,
  formatCompact,
} from '../../utils/liquidity';
import { priceDecimals } from '../../utils/format-numbers';

@Component({
  selector: 'app-order-book-drawer',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './order-book-drawer.component.html',
})
export class OrderBookDrawerComponent {
  private orderBookService = inject(OrderBookService);
  private destroyRef = inject(DestroyRef);
  private refreshTimer: ReturnType<typeof setInterval> | undefined;

  open = input(false);
  route = input<SpreadOpportunity | null>(null);
  notional = input(1000);
  quoteAsset = input('USD');

  closed = output<void>();

  loading = signal(false);
  error = signal<string | null>(null);
  data = signal<RouteOrderBookResponse | null>(null);
  lastFetchedAt = signal<number | null>(null);

  askLevels = computed(() => {
    const book = this.data()?.buyBook;
    if (!book?.asks?.length) return [];
    const maxQuote = Math.max(...book.asks.map(l => l.price * l.size), 1);
    return buildDepthLevels(book.asks, maxQuote);
  });

  bidLevels = computed(() => {
    const book = this.data()?.sellBook;
    if (!book?.bids?.length) return [];
    const maxQuote = Math.max(...book.bids.map(l => l.price * l.size), 1);
    return buildDepthLevels(book.bids, maxQuote);
  });

  askFillIndex = computed(() => findFillIndex(this.askLevels(), this.notional()));
  bidFillIndex = computed(() => findFillIndex(this.bidLevels(), this.notional()));

  formatCompact = formatCompact;

  constructor() {
    effect(() => {
      const isOpen = this.open();
      const selected = this.route();
      this.clearRefreshTimer();

      if (!isOpen || !selected) {
        this.data.set(null);
        this.error.set(null);
        this.loading.set(false);
        return;
      }

      this.fetchBooks(selected);
      this.refreshTimer = setInterval(() => this.fetchBooks(selected), 5000);
    });

    this.destroyRef.onDestroy(() => this.clearRefreshTimer());
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    if (this.open()) {
      this.close();
    }
  }

  close() {
    this.closed.emit();
  }

  refresh() {
    const selected = this.route();
    if (selected) {
      this.fetchBooks(selected);
    }
  }

  priceFormat(value: number) {
    return priceDecimals(value);
  }

  ageLabel(): string {
    const ts = this.lastFetchedAt();
    if (!ts) return '—';
    const seconds = Math.max(0, Math.floor((Date.now() - ts) / 1000));
    return `${seconds}s ago`;
  }

  private fetchBooks(route: SpreadOpportunity) {
    this.loading.set(true);
    this.error.set(null);

    this.orderBookService
      .fetchRouteBooks(route.symbol, route.buyExchange, route.sellExchange, 20)
      .subscribe({
        next: response => {
          this.data.set(response);
          this.lastFetchedAt.set(Date.now());
          this.loading.set(false);
          if (!response.buyBook && !response.sellBook) {
            this.error.set('Both order books failed to load.');
          }
        },
        error: err => {
          this.loading.set(false);
          this.error.set(err?.error?.error ?? 'Failed to load order books.');
        },
      });
  }

  private clearRefreshTimer() {
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = undefined;
    }
  }
}
