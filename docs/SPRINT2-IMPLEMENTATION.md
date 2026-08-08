# Sprint 2 Implementation — Realtime + Dashboard

**Status:** Implementation in progress.

---

## Overview

Sprint 2 delivers live streaming of the full spread matrix from the backend to an Angular dashboard,
with per-exchange health indicators and a real-time LIVE status badge. All data flows over WebSocket/
STOMP/SockJS; the frontend never polls.

---

## Step 0 — Close Sprint 1 prerequisites

### 0.1 Backend port: move to 8081

**Change:** `server.port=8081` in `application.properties`

**Why:** 8080 is held by Docker Desktop's backend process; nothing else breaks.

**Updates needed:**
- README: update all `localhost:8080` → `localhost:8081`
- `proxy.conf.json` (step 1 frontend): proxy `/api`, `/ws` to `:8081`
- `docker-compose.yml` (Sprint 3): map port in the backend service

### 0.2 Fix Testcontainers Docker detection

**File:** `~/.testcontainers.properties` (home directory, must exist before running tests)

```properties
docker.client.strategy=npipe
docker.host.override=npipe:////./pipe/dockerDesktopLinuxEngine
```

**Why:** Gradle tests probe the `default` Docker context (standard npipe), but your active context
is `desktop-linux` (the other npipe). This config tells Testcontainers to use the right one.

### 0.3 Run integration test

After 0.2 is set, `./gradlew build` should complete with 8 tests passing (7 unit + 1 integration).

```bash
./gradlew test --tests "com.cryptoarbitrage.monitor.repository.SpreadLogRepositoryIntegrationTest"
```

**Success:** Testcontainers spins up Postgres, Flyway runs, seeds are inserted, `SpreadLogRepository`
queries work, Docker container cleans up.

### 0.4 Write adapter tests

Three tests, one per adapter. Use a stubbed `ExchangeFunction` returning fixture JSON.

**Fixtures** — save from live probes already done, store in `src/test/resources/fixtures/`:

- `binance/btcusd.json` — live response to `/api/v3/ticker/bookTicker?symbol=BTCUSD`
- `kraken/xxbtzusd.json` — live response to `/0/public/Ticker?pair=XXBTZUSD`
- `coinbase/btcusd.json` — live response to `/products/BTC-USD/ticker`
- One error case per adapter: Kraken error array in 200, HTTP 429, missing field

**Test structure:**

```java
class KrakenAdapterTest {
  private KrakenAdapter adapter;
  
  @BeforeEach
  void setUp() {
    WebClient webClient = WebClient.builder()
      .baseUrl("https://api.kraken.com")
      .exchangeFunction(stubFunction) // Returns fixture or error
      .build();
    adapter = new KrakenAdapter(webClient, exchangeProperties);
  }
  
  @Test
  void testParseTicker_Successful() {
    // Assert symbol mapping, bid/ask parsed, receivedAt not null
  }
  
  @Test
  void testParseTicker_ErrorArray() {
    // Kraken's 200 response with "error": ["EAPI:Invalid key"]
    // Adapter should throw
  }
}
```

**Gate:** All three tests pass; each confirms symbol mapping, precision, and error shape.

### 0.5 Smoke test

Start Postgres and the app, call each endpoint, wait for cycles.

```bash
docker compose up -d postgres
cd backend && ./gradlew bootRun &
sleep 5

curl -s localhost:8081/api/pairs | jq '.[] | {symbol, active}'
curl -s localhost:8081/api/exchanges | jq '.[] | {exchange, available}'
curl -s localhost:8081/api/fees | jq '.[] | {exchange, takerFee}'

sleep 8  # Wait for first two poll cycles

curl -s localhost:8081/api/spreads/latest | jq '.[] | {symbol, buyExchange, sellExchange, netSpreadPercent}'
curl -s "localhost:8081/api/spreads/history?limit=10" | jq 'length'
curl -s localhost:8081/api/spreads/history -w "\n%{http_code}\n" | tail -1  # expect 400
```

**Success:** All five endpoints return 200 (or 400 for unbounded history); spreads appear after cycles
run; log shows "Poll cycle completed" every ~3s.

---

## Backend work

### 1. Cycle timestamp: add to CalculationResult

**Change:** `SpreadCalculationService.CalculationResult` adds one `Instant calculatedAt` field, set
once per cycle in `PollOrchestrationService.executePollCycle()` before invoking `calculateSpreads`.

**Why:** Today each `SpreadDto.from(SpreadOpportunity)` stamps its own `Instant.now()`, so a single
cycle's rows get different timestamps. When published as one snapshot, the UI's "last update" differs
per row, looking like a cache bug. One timestamp per cycle is the truth.

### 2. Fix cycle parallelism

**Current:** `fetchTickersInParallel` loops symbols sequentially, fanning out only across exchanges.
Two symbols × three exchanges = 6 calls, but two blocking round-trips per cycle.

**Fix:** Flatten to one `Flux` over all symbol × adapter pairs, collect, group by symbol.

```java
private Map<String, List<PriceTicker>> fetchTickersInParallel(List<TrackedPair> activePairs) {
  return Flux.fromIterable(activePairs)
    .flatMap(pair -> Flux.fromIterable(adapters)
      .flatMap(adapter -> adapter.getTicker(pair.getSymbol())
        .doOnNext(ticker -> availabilityStore.recordSuccess(ticker.exchange()))
        .onErrorResume(e -> Mono.empty())
      )
    )
    .collectMap(ticker -> ticker.symbol(), Function.identity(), HashMap::new, ArrayList::new)
    .block()  // Acceptable here; it's the poll cycle's outermost level
    .orElse(Collections.emptyMap());
}
```

### 3. WebSocket / STOMP / SockJS config

**File:** `src/main/java/com/cryptoarbitrage/monitor/config/WebSocketConfig.java`

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("http://localhost:*")  // Dev only; Sprint 3 externalizes
                .withSockJS();
    }
}
```

**Why:** In-memory broker sufficient for V1; SockJS enables fallback if WebSocket unavailable.

### 4. SpreadSnapshotDto + SpreadPublisher

**SpreadSnapshotDto** (`src/main/java/com/cryptoarbitrage/monitor/dto/SpreadSnapshotDto.java`):

```java
public record SpreadSnapshotDto(
    Instant calculatedAt,
    List<SpreadDto> matrix,              // Full directed matrix
    List<SpreadDto> bestPerSymbol,       // Server-picked highest net per symbol
    List<ExchangeStatusDto> exchanges,   // Freshness per exchange
    int freshExchangeCount,              // Count >= 2 for LIVE
    boolean live                         // freshExchangeCount >= 2 within 10s
) {}
```

**SpreadPublisher** (`src/main/java/com/cryptoarbitrage/monitor/service/SpreadPublisher.java`):

```java
@Service
public class SpreadPublisher {
    private final SimpMessagingTemplate template;
    private final ExchangeAvailabilityStore availabilityStore;
    private final AppProperties appProperties;

    public void publishSnapshot(SpreadCalculationService.CalculationResult result, Instant calculatedAt) {
        int freshCount = (int) Arrays.stream(Exchange.values())
            .filter(ex -> availabilityStore.isFresh(ex, appProperties.getPolling().getFreshnessWindowMs()))
            .count();
        
        boolean live = freshCount >= 2;
        
        SpreadSnapshotDto snapshot = new SpreadSnapshotDto(
            calculatedAt,
            result.fullMatrix.stream().map(SpreadDto::from).toList(),
            result.bestPerSymbol.values().stream().map(SpreadDto::from).toList(),
            Arrays.stream(Exchange.values())
                .map(ex -> ExchangeStatusDto.from(ex, availabilityStore.getLastReceivedAt(ex), 
                    availabilityStore.isFresh(ex, appProperties.getPolling().getFreshnessWindowMs())))
                .toList(),
            freshCount,
            live
        );
        
        template.convertAndSend("/topic/spreads", snapshot);
    }
}
```

**In PollOrchestrationService:** After `snapshotStore.update(result.fullMatrix)`, call
`spreadPublisher.publishSnapshot(result, calculatedAt)`. **Outside** the DB transaction so a publish
failure doesn't roll back the write, and a DB failure doesn't suppress the publish.

### 5. Housekeeping

**PollOrchestrationService:**
- Rename local `SpreadLog log` to `spreadLog` in `persistBestOpportunities` to avoid shadowing the field
- Drop the duplicate static logger

**FeeService:**
- Replace the one-call-per-exchange-per-cycle pattern with a cache refreshed on a separate schedule
- `@Scheduled(fixedDelayString = "${app.fee-refresh-interval-ms:60000}")` — default 60s, configurable

---

## Frontend work

### 1. Dev wiring — proxy.conf.json

**File:** `frontend/proxy.conf.json`

```json
{
  "/api": {
    "target": "http://localhost:8081",
    "secure": false,
    "changeOrigin": true
  },
  "/ws": {
    "target": "http://localhost:8081",
    "secure": false,
    "changeOrigin": true,
    "ws": true
  }
}
```

**Update `angular.json`:** Under `projects.frontend.architect.serve.options`, add
`"proxyConfig": "proxy.conf.json"`.

**Why:** Frontend calls `/api/*` and `/ws` as same-origin; ng serve proxies to the backend. No CORS
config needed, and the wiring is identical to Sprint 3 Nginx.

### 2. Models

**File:** `src/app/models/spread.model.ts`

```typescript
export interface PriceTicker {
  exchange: string;
  symbol: string;
  bid: number;
  ask: number;
}

export interface SpreadOpportunity {
  symbol: string;
  buyExchange: string;
  sellExchange: string;
  buyPrice: number;
  sellPrice: number;
  rawSpreadPercent: number;
  netSpreadPercent: number;
  calculatedAt?: string;  // ISO-8601
}

export interface ExchangeStatus {
  exchange: string;
  available: boolean;
  lastUpdate?: string;  // ISO-8601, nullable
  freshness: 'FRESH' | 'STALE' | 'NEVER';
}

export interface SpreadSnapshot {
  calculatedAt: string;
  matrix: SpreadOpportunity[];
  bestPerSymbol: SpreadOpportunity[];
  exchanges: ExchangeStatus[];
  freshExchangeCount: number;
  live: boolean;
}
```

### 3. WebSocket service

**File:** `src/app/services/websocket.service.ts`

```typescript
import { Injectable, signal, effect, NgZone } from '@angular/core';
import { Client, Frame } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { SpreadSnapshot } from '../models/spread.model';

@Injectable({
  providedIn: 'root'
})
export class WebsocketService {
  private client = new Client({
    brokerURL: undefined,  // Set below
    webSocketFactory: () => new SockJS('/ws'),
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000
  });

  snapshot = signal<SpreadSnapshot | null>(null);
  connection = signal<'connecting' | 'open' | 'closed'>('closed');

  private stalenesstimer: any;
  private readonly STALE_THRESHOLD_MS = 10000;

  constructor(private ngZone: NgZone) {
    this.client.onConnect = () => this.onConnect();
    this.client.onDisconnect = () => this.onDisconnect();
    this.client.onStompError = () => this.onError();
  }

  connect() {
    if (this.connection() !== 'closed') return;
    this.connection.set('connecting');
    this.client.activate();
  }

  disconnect() {
    this.client.deactivate();
    this.connection.set('closed');
  }

  private onConnect() {
    this.ngZone.run(() => this.connection.set('open'));
    this.client.subscribe('/topic/spreads', (message: Frame) => {
      this.ngZone.run(() => {
        this.snapshot.set(JSON.parse(message.body) as SpreadSnapshot);
        this.resetStalenessTimer();
      });
    });
  }

  private onDisconnect() {
    this.ngZone.run(() => this.connection.set('closed'));
  }

  private onError() {
    this.ngZone.run(() => this.connection.set('closed'));
  }

  private resetStalenessTimer() {
    clearTimeout(this.stalenesstimer);
    this.stalenesstimer = setTimeout(() => {
      this.snapshot.update(s => s ? { ...s, live: false } : null);
    }, this.STALE_THRESHOLD_MS);
  }
}
```

**Polyfill note:** Add to `main.ts` before bootstrap:

```typescript
(window as any).global = window;  // Needed for sockjs-client under Angular's build
```

### 4. REST services (bootstrap only)

**File:** `src/app/services/spread.service.ts`

```typescript
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { SpreadOpportunity } from '../models/spread.model';

@Injectable({
  providedIn: 'root'
})
export class SpreadService {
  constructor(private http: HttpClient) {}

  getLatest(): Observable<SpreadOpportunity[]> {
    return this.http.get<SpreadOpportunity[]>('/api/spreads/latest');
  }

  getHistory(limit: number, from?: string, to?: string): Observable<SpreadOpportunity[]> {
    let url = `/api/spreads/history?limit=${limit}`;
    if (from) url += `&from=${from}`;
    if (to) url += `&to=${to}`;
    return this.http.get<SpreadOpportunity[]>(url);
  }
}
```

**File:** `src/app/services/exchange.service.ts` — similarly for `/api/pairs`, `/api/exchanges`, `/api/fees`.

### 5. Components

**dashboard/dashboard.component.ts** — container, owns notional signal, sets up WebSocket:

```typescript
@Component({
  selector: 'app-dashboard',
  imports: [SpreadDetailComponent, SpreadTableComponent, ConnectionStatusComponent],
  template: `
    <div class="p-4">
      <app-connection-status />
      <div class="mt-4 flex gap-4">
        <input [(ngModel)]="notional" type="number" placeholder="Investment ($)" />
      </div>
      <div class="mt-4 grid grid-cols-2 gap-4">
        <app-spread-detail [opportunities]="opportunities()" [notional]="notional()" />
        <app-spread-table [matrix]="matrix()" [notional]="notional()" />
      </div>
    </div>
  `
})
export class DashboardComponent implements OnInit {
  notional = signal(1000);
  
  opportunities = computed(() => this.websocket.snapshot()?.bestPerSymbol ?? []);
  matrix = computed(() => this.websocket.snapshot()?.matrix ?? []);

  constructor(public websocket: WebsocketService) {}

  ngOnInit() {
    this.websocket.connect();
  }

  ngOnDestroy() {
    this.websocket.disconnect();
  }
}
```

**spread-detail/spread-detail.component.ts** — list of best per symbol with profit calc:

```typescript
@Component({
  selector: 'app-spread-detail',
  template: `
    @for (opp of opportunities(); track opp.symbol) {
      <div class="border p-4 rounded" [class.bg-red-50]="opp.netSpreadPercent < 0">
        <h3>{{ opp.symbol }}</h3>
        <p>Buy {{ opp.buyExchange }} @ {{ opp.buyPrice | number:'1.2-8' }}</p>
        <p>Sell {{ opp.sellExchange }} @ {{ opp.sellPrice | number:'1.2-8' }}</p>
        <p>Raw: {{ opp.rawSpreadPercent | number:'1.4-4' }}%</p>
        <p>Net: {{ opp.netSpreadPercent | number:'1.4-4' }}%</p>
        <p class="text-lg font-bold">
          Est. Profit: ${{ estimatedProfit(opp) | number:'1.2-2' }}
        </p>
      </div>
    }
  `
})
export class SpreadDetailComponent {
  @Input() opportunities: SpreadOpportunity[] = [];
  @Input() notional = 1000;

  estimatedProfit(opp: SpreadOpportunity): number {
    return (this.notional * opp.netSpreadPercent) / 100;
  }
}
```

**spread-table/spread-table.component.ts** — full matrix:

```typescript
@Component({
  selector: 'app-spread-table',
  template: `
    <table class="w-full border">
      <thead>
        <tr class="bg-gray-100">
          <th>Buy</th><th>Sell</th><th>Buy Price</th><th>Sell Price</th><th>Raw %</th><th>Net %</th>
        </tr>
      </thead>
      <tbody>
        @for (row of matrix(); track row.symbol + row.buyExchange + row.sellExchange) {
          <tr [class.bg-green-50]="row.netSpreadPercent > 0" [class.bg-red-50]="row.netSpreadPercent < 0">
            <td>{{ row.buyExchange }}</td>
            <td>{{ row.sellExchange }}</td>
            <td>{{ row.buyPrice | number:'1.2-8' }}</td>
            <td>{{ row.sellPrice | number:'1.2-8' }}</td>
            <td>{{ row.rawSpreadPercent | number:'1.4-4' }}%</td>
            <td>{{ row.netSpreadPercent | number:'1.4-4' }}%</td>
          </tr>
        }
      </tbody>
    </table>
  `
})
export class SpreadTableComponent {
  @Input() matrix: SpreadOpportunity[] = [];
  @Input() notional = 1000;
}
```

**connection-status/connection-status.component.ts** — LIVE badge + per-exchange chips:

```typescript
@Component({
  selector: 'app-connection-status',
  template: `
    <div class="flex items-center gap-4">
      <div class="text-lg font-bold" [class.text-green-600]="snapshot()?.live" [class.text-red-600]="!snapshot()?.live">
        {{ snapshot()?.live ? '🟢 LIVE' : '🔴 DEGRADED' }}
      </div>
      <p class="text-sm">{{ connectionAge() | async }}</p>
      <div class="flex gap-2">
        @for (ex of snapshot()?.exchanges ?? []; track ex.exchange) {
          <span class="px-2 py-1 rounded text-sm"
                [class.bg-green-200]="ex.freshness === 'FRESH'"
                [class.bg-yellow-200]="ex.freshness === 'STALE'"
                [class.bg-gray-200]="ex.freshness === 'NEVER'">
            {{ ex.exchange }}: {{ ex.freshness }}
          </span>
        }
      </div>
    </div>
  `
})
export class ConnectionStatusComponent {
  snapshot = toSignal(this.websocket.snapshot, { initialValue: null });
  connectionAge = this.websocket.snapshot.pipe(
    switchMap(s => s ? interval(1000).pipe(map(() => this.formatAge(s.calculatedAt))) : of('—'))
  );

  constructor(public websocket: WebsocketService) {}

  private formatAge(iso: string): string {
    const age = (Date.now() - new Date(iso).getTime()) / 1000;
    return `${Math.floor(age)}s ago`;
  }
}
```

### 6. Update app.routes.ts

```typescript
export const routes: Routes = [
  {
    path: '',
    component: DashboardComponent
  }
];
```

### 7. Update styles.css

Add Tailwind and reset defaults.

---

## Sprint 2 Refinements Implementation

### Backend: 1. Quote asset threading and validation

**PriceTicker.java** — add fields:

```java
public record PriceTicker(
    Exchange exchange,
    String symbol,           // Internal: BTC/USD
    String nativeSymbol,     // Exchange-specific: BTCUSD, XXBTZUSD, BTC-USD
    String quoteAsset,       // USD, USDT, EUR, etc.
    BigDecimal bid,
    BigDecimal ask,
    Instant receivedAt
) {}
```

**ExchangeProperties.java** — restructure symbol mapping:

```
exchange.binance.markets.BTC_USD.native-symbol=BTCUSD
exchange.binance.markets.BTC_USD.quote-asset=USD
exchange.binance.markets.ETH_USD.native-symbol=ETHUSD
exchange.binance.markets.ETH_USD.quote-asset=USD

exchange.kraken.markets.BTC_USD.native-symbol=XXBTZUSD
exchange.kraken.markets.BTC_USD.quote-asset=USD
exchange.kraken.markets.ETH_USD.native-symbol=XETHZUSD
exchange.kraken.markets.ETH_USD.quote-asset=USD

exchange.coinbase.markets.BTC_USD.native-symbol=BTC-USD
exchange.coinbase.markets.BTC_USD.quote-asset=USD
exchange.coinbase.markets.ETH_USD.native-symbol=ETH-USD
exchange.coinbase.markets.ETH_USD.quote-asset=USD
```

**Add config validator** — new file `MarketConfigValidator.java`:

```java
@Component
public class MarketConfigValidator {
    private static final Logger log = LoggerFactory.getLogger(MarketConfigValidator.class);
    private final ExchangeProperties properties;

    @PostConstruct
    public void validate() {
        // For each internal symbol, check all exchanges agree on quote asset
        Map<String, Set<String>> symbolToQuotes = new HashMap<>();
        for (var adapter : properties.getAdapters().values()) {
            for (var market : adapter.getMarkets().values()) {
                symbolToQuotes.computeIfAbsent(market.getSymbol(), k -> new HashSet<>())
                    .add(market.getQuoteAsset());
            }
        }
        
        for (var entry : symbolToQuotes.entrySet()) {
            if (entry.getValue().size() > 1) {
                log.warn("Symbol {} configured with mixed quote assets: {}", 
                    entry.getKey(), entry.getValue());
            }
        }
    }
}
```

**SpreadOpportunity and SpreadDto** — add quote asset fields:

```java
public static class SpreadOpportunity {
    public final String symbol;
    public final Exchange buyExchange;
    public final String buyNativeSymbol;
    public final String buyQuoteAsset;
    // ... sell equivalents ...
    public final BigDecimal buyPrice;
    // ... rest unchanged ...
}

public record SpreadDto(
    String symbol,
    String buyExchange,
    String buyNativeSymbol,
    String buyQuoteAsset,
    // ... sell equivalents ...
    BigDecimal buyPrice,
    // ... rest unchanged ...
) {}
```

**Update adapters** — each adapter now threads `nativeSymbol` and `quoteAsset` into `PriceTicker`:

```java
return new PriceTicker(
    Exchange.BINANCE,
    internalSymbol,
    nativeSymbol,
    "USD",  // or fetched from config
    bid,
    ask,
    Instant.now()
);
```

**Thread through SpreadCalculationService** — add fields to `SpreadOpportunity` constructor:

```java
SpreadOpportunity opp = new SpreadOpportunity(
    symbol,
    buyTicker.exchange(),
    buyTicker.nativeSymbol(),
    buyTicker.quoteAsset(),
    sellTicker.exchange(),
    sellTicker.nativeSymbol(),
    sellTicker.quoteAsset(),
    buyPrice,
    sellPrice,
    rawSpreadPercent,
    netSpreadPercent
);
```

### Backend: 5. GET /api/config endpoint

**New AppConfigDto.java**:

```java
public record AppConfigDto(
    int defaultNotional,
    int freshnessWindowMs,
    double neutralEpsilonPercent,
    List<FeeDto> fees
) {}
```

**Add to SpreadController**:

```java
@GetMapping("/config")
public ResponseEntity<AppConfigDto> getConfig() {
    var fees = feeService.getAllFees().entrySet().stream()
        .map(e -> new FeeDto(e.getKey().name(), e.getValue(), Instant.now()))
        .toList();
    
    return ResponseEntity.ok(new AppConfigDto(
        appProperties.getInvestment().getDefaultNotional(),
        appProperties.getPolling().getFreshnessWindowMs(),
        0.001,  // neutralEpsilonPercent
        fees
    ));
}
```

### Frontend: 1. Spread state classifier utility

**New file: `src/app/utils/spread-state.ts`**:

```typescript
export type SpreadState = 'POTENTIAL' | 'NO_OPPORTUNITY' | 'NEUTRAL';

export const NEUTRAL_EPSILON_PERCENT = 0.001;

export function getSpreadState(netPercent: number): SpreadState {
  if (netPercent > NEUTRAL_EPSILON_PERCENT) return 'POTENTIAL';
  if (netPercent < -NEUTRAL_EPSILON_PERCENT) return 'NO_OPPORTUNITY';
  return 'NEUTRAL';
}

export function getStateClasses(state: SpreadState): Record<string, boolean> {
  const baseClasses = {
    'p-4': true,
    'rounded-lg': true,
    'border': true,
  };

  switch (state) {
    case 'POTENTIAL':
      return {
        ...baseClasses,
        'bg-green-50': true,
        'border-green-200': true,
      };
    case 'NO_OPPORTUNITY':
      return {
        ...baseClasses,
        'bg-red-50': true,
        'border-red-200': true,
      };
    case 'NEUTRAL':
      return {
        ...baseClasses,
        'bg-gray-50': true,
        'border-gray-200': true,
      };
  }
}

export function getStateLabel(state: SpreadState, netPercent: number): string {
  switch (state) {
    case 'POTENTIAL':
      return `POTENTIAL OPPORTUNITY — Net spread: +${netPercent.toFixed(4)}%`;
    case 'NO_OPPORTUNITY':
      return `NO POSITIVE OPPORTUNITY — Best net spread: ${netPercent.toFixed(4)}%`;
    case 'NEUTRAL':
      return `NO MEANINGFUL SPREAD — Net spread: ${netPercent.toFixed(4)}%`;
  }
}

export function getIndicatorEmoji(state: SpreadState): string {
  switch (state) {
    case 'POTENTIAL': return '🟢';
    case 'NO_OPPORTUNITY': return '🔴';
    case 'NEUTRAL': return '⚪';
  }
}
```

### Frontend: 2-3. Updated spread-detail component

**spread-detail.component.ts**:

```typescript
import { Component, Input, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SpreadOpportunity } from '../../models/spread.model';
import { getSpreadState, getStateClasses, getStateLabel, getIndicatorEmoji } from '../../utils/spread-state';

@Component({
  selector: 'app-spread-detail',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './spread-detail.component.html',
  styles: []
})
export class SpreadDetailComponent {
  @Input() opportunities: SpreadOpportunity[] = [];
  @Input() notional = 1000;

  estimatedProfit(opp: SpreadOpportunity): number {
    return (this.notional * opp.netSpreadPercent) / 100;
  }

  getState(netPercent: number) {
    return getSpreadState(netPercent);
  }

  getClasses(netPercent: number) {
    return getStateClasses(this.getState(netPercent));
  }

  getLabel(netPercent: number) {
    return getStateLabel(this.getState(netPercent), netPercent);
  }

  getEmoji(netPercent: number) {
    return getIndicatorEmoji(this.getState(netPercent));
  }
}
```

**spread-detail.component.html** (rewrite):

```html
<div>
  <h2 class="text-lg font-bold mb-4">Best Current Spreads</h2>
  @if (opportunities.length === 0) {
    <p class="text-gray-500">Waiting for data...</p>
  }
  <div class="space-y-4">
    @for (opp of opportunities; track opp.symbol) {
      <div [ngClass]="getClasses(opp.netSpreadPercent)">
        <div class="flex justify-between items-start mb-2">
          <h3 class="text-lg font-bold">{{ opp.symbol }}</h3>
          <span class="text-2xl">{{ getEmoji(opp.netSpreadPercent) }}</span>
        </div>

        <div class="text-sm font-semibold mb-3"
             [ngClass]="opp.netSpreadPercent > 0.001 ? 'text-green-700' : (opp.netSpreadPercent < -0.001 ? 'text-red-700' : 'text-gray-700')">
          {{ getLabel(opp.netSpreadPercent) }}
        </div>

        <div class="grid grid-cols-2 gap-4 text-sm mb-3">
          <div>
            <p class="text-gray-600">Buy on: {{ opp.buyExchange }}</p>
            <p class="font-mono text-lg">{{ opp.buyPrice | number:'1.2-8' }}</p>
          </div>
          <div>
            <p class="text-gray-600">Sell on: {{ opp.sellExchange }}</p>
            <p class="font-mono text-lg">{{ opp.sellPrice | number:'1.2-8' }}</p>
          </div>
        </div>

        <div class="border-t pt-3">
          <p class="text-sm text-gray-600">Raw Spread: {{ opp.rawSpreadPercent | number:'1.4-4' }}%</p>
          <p class="text-xl font-bold" 
             [ngClass]="estimatedProfit(opp) < 0 ? 'text-red-600' : (estimatedProfit(opp) > 0 ? 'text-green-600' : 'text-gray-600')">
            Estimated profit: ${{ estimatedProfit(opp) | number:'1.2-2' }}
          </p>
        </div>
      </div>
    }
  </div>
</div>
```

### Frontend: 4. Matrix grouping and sorting

**spread-table.component.ts** (rewrite):

```typescript
import { Component, Input, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SpreadOpportunity } from '../../models/spread.model';
import { getStateClasses, getSpreadState } from '../../utils/spread-state';

interface MatrixGroup {
  symbol: string;
  rows: SpreadOpportunity[];
}

@Component({
  selector: 'app-spread-table',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './spread-table.component.html',
  styles: []
})
export class SpreadTableComponent {
  @Input() matrix: SpreadOpportunity[] = [];
  @Input() notional = 1000;

  groupedMatrix = computed(() => {
    const groups = new Map<string, SpreadOpportunity[]>();
    
    for (const row of this.matrix) {
      if (!groups.has(row.symbol)) {
        groups.set(row.symbol, []);
      }
      groups.get(row.symbol)!.push(row);
    }

    return Array.from(groups.entries())
      .map(([symbol, rows]) => ({
        symbol,
        rows: rows.sort((a, b) => {
          // Sort by net spread desc, then raw spread desc, then buy exchange name
          if (b.netSpreadPercent !== a.netSpreadPercent) {
            return b.netSpreadPercent.valueOf() - a.netSpreadPercent.valueOf();
          }
          if (b.rawSpreadPercent !== a.rawSpreadPercent) {
            return b.rawSpreadPercent.valueOf() - a.rawSpreadPercent.valueOf();
          }
          return a.buyExchange.localeCompare(b.buyExchange);
        })
      }))
      .sort((a, b) => a.symbol.localeCompare(b.symbol));
  });

  getRowClass(netPercent: number) {
    return getStateClasses(getSpreadState(netPercent));
  }
}
```

**spread-table.component.html** (rewrite with grouping):

```html
<div class="overflow-x-auto">
  <h2 class="text-lg font-bold mb-4">Full Matrix</h2>
  @if (matrix.length === 0) {
    <p class="text-gray-500 text-sm">No data yet</p>
  }
  
  @for (group of groupedMatrix(); track group.symbol) {
    <div class="mb-6">
      <h3 class="text-md font-semibold mb-2 text-gray-700">{{ group.symbol }}</h3>
      <table class="w-full text-xs border-collapse">
        <thead>
          <tr class="bg-gray-200">
            <th class="border p-2 text-left">Buy</th>
            <th class="border p-2 text-left">Sell</th>
            <th class="border p-2 text-right">Buy $</th>
            <th class="border p-2 text-right">Sell $</th>
            <th class="border p-2 text-right">Raw %</th>
            <th class="border p-2 text-right">Fees %</th>
            <th class="border p-2 text-right">Net %</th>
          </tr>
        </thead>
        <tbody>
          @for (row of group.rows; track row.buyExchange + row.sellExchange) {
            <tr [ngClass]="row.netSpreadPercent > 0 ? 'bg-green-50' : (row.netSpreadPercent < 0 ? 'bg-red-50' : '')">
              <td class="border p-2">{{ row.buyExchange }}</td>
              <td class="border p-2">{{ row.sellExchange }}</td>
              <td class="border p-2 text-right font-mono">{{ row.buyPrice | number:'1.2-4' }}</td>
              <td class="border p-2 text-right font-mono">{{ row.sellPrice | number:'1.2-4' }}</td>
              <td class="border p-2 text-right">{{ row.rawSpreadPercent | number:'1.2-4' }}%</td>
              <td class="border p-2 text-right">{{ (row.rawSpreadPercent - row.netSpreadPercent) | number:'1.2-2' }}%</td>
              <td class="border p-2 text-right font-bold">{{ row.netSpreadPercent | number:'1.2-4' }}%</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  }
</div>
```

### Frontend: 5. Notional quick-select + config

**Update dashboard.component.html**:

```html
<div class="min-h-screen bg-gray-50 p-6">
  <div class="max-w-7xl mx-auto">
    <h1 class="text-4xl font-bold mb-6">Crypto Arbitrage Monitor</h1>

    <!-- Connection Status -->
    <app-connection-status />

    <!-- Notional Input with Quick Select -->
    <div class="mt-6 bg-white p-4 rounded-lg shadow">
      <label class="block text-sm font-medium mb-3">
        Investment Amount ($):
      </label>
      <div class="flex gap-2 mb-3">
        @for (amount of [100, 1000, 5000, 10000, 50000]; track amount) {
          <button 
            (click)="notional.set(amount)"
            [class.bg-blue-600]="notional() === amount"
            [class.text-white]="notional() === amount"
            [class.bg-gray-200]="notional() !== amount"
            class="px-3 py-2 rounded text-sm font-medium transition">
            ${{ amount | number:'0' }}
          </button>
        }
      </div>
      <input
        [(ngModel)]="notional"
        type="number"
        min="1"
        max="10000000"
        class="px-3 py-2 border rounded w-40"
      />
    </div>

    <!-- Main Grid -->
    <div class="mt-6 grid grid-cols-1 lg:grid-cols-3 gap-6">
      <!-- Best Opportunities -->
      <div class="lg:col-span-2">
        <app-spread-detail
          [opportunities]="opportunities()"
          [notional]="notional()"
        />
      </div>

      <!-- Matrix Table -->
      <div>
        <app-spread-table
          [matrix]="matrix()"
          [notional]="notional()"
        />
      </div>
    </div>
  </div>
</div>
```

**Update dashboard.component.ts** to load config:

```typescript
export class DashboardComponent implements OnInit, OnDestroy {
  notional = signal(1000);
  config = signal<any>(null);

  opportunities = computed(() => this.websocket.snapshot()?.bestPerSymbol ?? []);
  matrix = computed(() => this.websocket.snapshot()?.matrix ?? []);

  constructor(
    public websocket: WebsocketService,
    private http: HttpClient
  ) {}

  ngOnInit() {
    this.http.get<any>('/api/config').subscribe(cfg => this.config.set(cfg));
    this.websocket.connect();
  }

  ngOnDestroy() {
    this.websocket.disconnect();
  }
}
```

### Frontend: 6. Client-side freshness ticker

**connection-status.component.ts** (complete rewrite):

```typescript
import { Component, signal, effect, OnInit, OnDestroy, NgZone } from '@angular/core';
import { CommonModule } from '@angular/common';
import { WebsocketService } from '../../services/websocket.service';

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
    private ngZone: NgZone
  ) {
    effect(() => {
      const snap = this.websocket.snapshot();
      const currentNow = this.now();
      
      if (!snap) {
        this.lastMessageAge.set('—');
        this.badge.set('STALE');
        return;
      }

      const receivedAtMs = new Date(snap.calculatedAt).getTime();
      const ageSeconds = (currentNow - receivedAtMs) / 1000;

      if (ageSeconds > 10) {
        this.lastMessageAge.set(`Updated ${Math.floor(ageSeconds)}s ago`);
        this.badge.set('STALE');
      } else if (!snap.live) {
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
      case 'LIVE': return '🟢';
      case 'DEGRADED': return '🟡';
      case 'STALE': return '🔴';
    }
  }
}
```

**connection-status.component.html** (rewrite):

```html
<div class="bg-white p-4 rounded-lg shadow">
  <div class="flex items-center gap-4">
    <!-- Status Badge -->
    <div class="flex items-center gap-2">
      <span class="text-2xl">{{ getBadgeEmoji() }}</span>
      <span class="text-lg font-bold"
            [class.text-green-600]="badge() === 'LIVE'"
            [class.text-yellow-600]="badge() === 'DEGRADED'"
            [class.text-red-600]="badge() === 'STALE'">
        {{ badge() }}
      </span>
    </div>

    <!-- Last Update Time -->
    <p class="text-sm text-gray-600">
      {{ lastMessageAge() }}
    </p>

    <!-- Exchange Status Chips -->
    <div class="flex gap-2 flex-wrap">
      @for (ex of snapshot()?.exchanges ?? []; track ex.exchange) {
        <span class="px-2 py-1 rounded text-xs font-semibold"
              [ngClass]="getExchangeClass(ex.freshness)">
          {{ ex.exchange }}: {{ ex.freshness }}
        </span>
      }
    </div>
  </div>
</div>
```

**websocket.service.ts** — remove the `live` mutation:

```typescript
export class WebsocketService {
  // ... other code ...
  
  private resetStalenessTimer() {
    clearTimeout(this.stalenessTimer);
    // Simply reset; don't mutate snapshot.live
    // The UI derives STALE state from age, not from this signal mutation
  }
}
```

### Testing updates

**Backend: SpreadCalculationServiceTest** — add test case with exact screenshot numbers:

```java
@Test
void testCalculateSpread_Kraken_to_Binance_matches_screenshot() {
  // Buy on Kraken: 64967.30, Sell on Binance: 64963.00
  // Kraken fee: 0.26%, Binance fee: 0.1%
  // Expected: net spread = -0.3675%
  
  PriceTicker buyTicker = new PriceTicker(
    Exchange.KRAKEN, "BTC/USD", "XXBTZUSD", "USD",
    new BigDecimal("64961.00"), new BigDecimal("64967.30"), Instant.now()
  );
  PriceTicker sellTicker = new PriceTicker(
    Exchange.BINANCE, "BTC/USD", "BTCUSD", "USD",
    new BigDecimal("64963.00"), new BigDecimal("64968.00"), Instant.now()
  );
  
  Map<Exchange, BigDecimal> fees = Map.of(
    Exchange.KRAKEN, new BigDecimal("0.0026"),
    Exchange.BINANCE, new BigDecimal("0.001")
  );
  
  var result = service.calculateSpreads(
    Map.of("BTC/USD", List.of(buyTicker, sellTicker)),
    fees
  );
  
  var opportunity = result.fullMatrix.stream()
    .filter(o -> o.buyExchange == Exchange.KRAKEN && o.sellExchange == Exchange.BINANCE)
    .findFirst()
    .orElseThrow();
  
  assertEquals(new BigDecimal("-0.3675"), opportunity.netSpreadPercent.setScale(4, RoundingMode.HALF_UP));
}
```

**Frontend: spread-state.spec.ts** — test classifier boundaries:

```typescript
describe('spreadState', () => {
  it('should classify potential opportunity', () => {
    expect(getSpreadState(0.5)).toBe('POTENTIAL');
  });

  it('should classify no opportunity', () => {
    expect(getSpreadState(-0.5)).toBe('NO_OPPORTUNITY');
  });

  it('should classify neutral at epsilon boundary', () => {
    expect(getSpreadState(0.0005)).toBe('NEUTRAL');
    expect(getSpreadState(-0.0005)).toBe('NEUTRAL');
  });

  it('should classify just outside neutral', () => {
    expect(getSpreadState(0.0011)).toBe('POTENTIAL');
    expect(getSpreadState(-0.0011)).toBe('NO_OPPORTUNITY');
  });
});
```

---

## Bitget, KuCoin, and the USD/USDT Toggle — Implementation

**Status: implemented and verified.** Backend compiles and its unit-test suite passes on JDK 17
(`./gradlew test`, 31/32 tests green — the one failure, `SpreadLogRepositoryIntegrationTest`, needs
a local Docker daemon for Testcontainers and is unrelated to this change). Frontend builds clean
with `ng build` (zero errors, zero warnings after a minor template touch-up). All API shapes below
were verified against the **live** Bitget and KuCoin public endpoints before being encoded into
adapters, fixtures, and config — not assumed from documentation.

### Verified availability matrix

| Venue | BTC/USD | ETH/USD | BTC/USDT | ETH/USDT |
|---|---|---|---|---|
| Binance | ✓ `BTCUSD` | ✓ `ETHUSD` | ✓ `BTCUSDT` | ✓ `ETHUSDT` |
| Kraken | ✓ `XXBTZUSD` | ✓ `XETHZUSD` | ✓ `XBTUSDT` | ✓ `ETHUSDT` |
| Coinbase | ✓ `BTC-USD` | ✓ `ETH-USD` | ✓ `BTC-USDT` | ✓ `ETH-USDT` |
| Bitget | HTTP 400, code `40034` | — | ✓ `BTCUSDT` | ✓ `ETHUSDT` |
| KuCoin | HTTP 200, `data: null` | — | ✓ `BTC-USDT` | ✓ `ETH-USDT` |

### Backend: `ExchangeProperties` — flat fields → map-based nested markets

**Before:** three hardcoded fields (`binance`, `kraken`, `coinbase`), each with its own getter/
setter, plus `getAdapters()` rebuilding a `HashMap` on every call. Symbol mapping was
`Map<String,String> symbolMap` — no way to express which quote asset a market used, or that a
venue simply didn't offer a market at all.

**After** ([ExchangeProperties.java](../backend/src/main/java/com/cryptoarbitrage/monitor/config/ExchangeProperties.java)):

```java
@ConfigurationProperties(prefix = "exchange")
public class ExchangeProperties {
    private Map<String, ExchangeConfig> adapters = new HashMap<>();   // key: "binance", "bitget", ...

    public static class ExchangeConfig {
        private String baseUrl;
        private long connectTimeoutMs = 5000;
        private long responseTimeoutMs = 10000;
        private Map<String, MarketConfig> markets = new HashMap<>();  // key: "BTC_USD", "BTC_USDT", ...

        public MarketConfig getMarket(String internalSymbol) {
            return markets.get(internalSymbol.replace("/", "_"));
        }
    }

    public static class MarketConfig {
        private String nativeSymbol;   // e.g. "BTCUSDT", "XBTUSDT", "BTC-USDT"
        private String quoteAsset;     // "USD" or "USDT"
    }

    public Set<String> getOfferedQuoteAssets(Exchange exchange) {
        // distinct quoteAsset values across this venue's markets — feeds ExchangeStatusDto
        // and the frontend's chip filter
    }
}
```

A venue's `markets` map is now the single source of truth for what it offers. Absence of a
`BTC_USD` entry for Bitget **is** the fact that Bitget doesn't list `BTC/USD` — nothing else needs
to encode that separately.

`application.properties` gained the full nested config for all five venues:

```properties
exchange.adapters.bitget.base-url=https://api.bitget.com
exchange.adapters.bitget.markets.BTC_USDT.native-symbol=BTCUSDT
exchange.adapters.bitget.markets.BTC_USDT.quote-asset=USDT
exchange.adapters.bitget.markets.ETH_USDT.native-symbol=ETHUSDT
exchange.adapters.bitget.markets.ETH_USDT.quote-asset=USDT
# no BTC_USD / ETH_USD entries — Bitget does not list them

exchange.adapters.kraken.markets.BTC_USDT.native-symbol=XBTUSDT   # not XXBTZUSDT — see note below
exchange.adapters.kraken.markets.BTC_USDT.quote-asset=USDT
```

**`WebClientConfig`** kept five explicit `@Bean` methods rather than dynamically registering
`WebClient` singletons from the map. A `@PostConstruct`-driven dynamic-registration approach was
drafted and rejected: adapters resolve their client via `@Qualifier("bitgetWebClient")` constructor
injection, and Spring's bean-creation order relative to another bean's `@PostConstruct` is not
guaranteed — a race there would surface as an intermittent "no qualifying bean" startup failure.
Five one-line `@Bean` methods reading from the map by key carry none of that risk.

### Backend: not-offered vs failed — `ExchangeAdapter#supports`

```java
public interface ExchangeAdapter {
    Exchange getExchange();
    boolean supports(String internalSymbol);   // NEW
    Mono<PriceTicker> getTicker(String internalSymbol);
}
```

Every adapter implements it as `market(internalSymbol) != null`. `PollOrchestrationService` checks
`supports()` before building any request:

```java
for (TrackedPair pair : activePairs) {
    for (ExchangeAdapter adapter : adapters) {
        if (!adapter.supports(pair.getSymbol())) {
            continue;   // no request, no availability-store update, no WARN log
        }
        requests.add(adapter.getTicker(pair.getSymbol())
                .doOnNext(ticker -> availabilityStore.recordSuccess(ticker.exchange(), pair.getSymbol()))
                .onErrorResume(e -> { log.warn(...); return Mono.empty(); }));
    }
}
```

Without this, Bitget would be polled for `BTC/USD` every 3 seconds forever, log a warning every
cycle, and render as `NEVER` in the UI — indistinguishable from an actual outage.

### Backend: per-(exchange, symbol) freshness

**[ExchangeAvailabilityStore.java](../backend/src/main/java/com/cryptoarbitrage/monitor/service/ExchangeAvailabilityStore.java)**
widened its key from `Exchange` to `record Key(Exchange exchange, String symbol)`. New methods:

- `recordSuccess(Exchange, String symbol)`, `isFresh(Exchange, String symbol, long)` — per-pair facts
- `countFreshForSymbol(String symbol, long)` — distinct exchanges fresh for one symbol; the direct
  input to per-quote-asset LIVE computation
- `isFreshAny(Exchange, long)`, `getLastReceivedAtAny(Exchange)` — aggregate across whatever
  symbols that exchange has ever reported for, used for the venue-level chip

**[SpreadPublisher.java](../backend/src/main/java/com/cryptoarbitrage/monitor/service/SpreadPublisher.java)**
now groups active `tracked_pair` rows by `quoteCurrency` (the authoritative per-symbol mapping —
not inferred from live ticker data, so it stays correct even when a quote universe has zero fresh
venues) and publishes:

```java
Map<String, Boolean> liveByQuote;       // {"USD": false, "USDT": true}
Map<String, Integer> freshCountByQuote; // {"USD": 0, "USDT": 2}
```

alongside the pre-existing global `freshExchangeCount` / `live` (kept for any consumer that only
wants an overall signal). This is the fix for the bug the design exists to prevent: three USD
venues down while Bitget/KuCoin (USDT-only) are healthy previously read as globally LIVE
(`freshCount ≥ 2`) while the USD view being displayed was actually empty — now `liveByQuote.USD` is
independently `false`.

`ExchangeStatusDto` gained `offeredQuoteAssets: List<String>`, sourced from
`exchangeProperties.getOfferedQuoteAssets(exchange)`, so the frontend can hide a venue's chip
entirely when it doesn't offer the selected quote asset, rather than showing it as `NEVER`.

### Backend: `BitgetAdapter` and `KuCoinAdapter`

**BitgetAdapter** — `GET /api/v2/spot/market/tickers?symbol={native}`. Response envelope is
`{"code": "00000", "data": [{...}]}` — note `data` is an **array**, the only adapter where that's
true. Parsing checks `code != "00000"` first (independent of HTTP status, since a hypothetical
future error path could return 200 with a failure code), then checks for an empty `data` array.
Live-verified error case: unknown symbol → HTTP 400, code `"40034"`,
`"Parameter BTCUSD does not exist"`.

**KuCoinAdapter** — `GET /api/v1/market/orderbook/level1?symbol={native}`. Response is
`{"code": "200000", "data": {"bestBid": "...", "bestAsk": "..."}}`. **The gotcha, verified live:**
an unknown symbol returns **HTTP 200** with `{"code":"200000","data":null}`. The adapter's existing
`onStatus(status -> !status.is2xxSuccessful(), ...)` pattern — reused from every other adapter —
cannot catch this at all, since the status genuinely is 200. `parseTicker` has an explicit guard:

```java
JsonNode data = json.get("data");
if (data == null || data.isNull() || data.isMissingNode()) {
    throw new IllegalArgumentException("KuCoin: no data for symbol (unknown or delisted market)");
}
```

Without this check, `data.get("bestBid")` on a `NullNode` throws an unguarded `NullPointerException`
instead of resolving to a clean, logged adapter failure. Covered by
`KuCoinAdapterTest#testGetTicker_NullDataOnHttp200_ReturnsEmptyNotNpe`, which asserts
`assertDoesNotThrow(...)` around exactly this call path.

Both adapters set `quoteAsset = market.getQuoteAsset()` (i.e. `"USDT"`) on the `PriceTicker` they
return — the field added earlier in Sprint 2 for exactly this purpose.

### Backend: `Exchange` enum, `V3` migration, poll-cycle flattening

`Exchange` gained `BITGET`, `KUCOIN`. New migration
[V3__add_usdt_universe.sql](../backend/src/main/resources/db/migration/V3__add_usdt_universe.sql):

```sql
INSERT INTO tracked_pair (symbol, base_currency, quote_currency, active) VALUES
    ('BTC/USDT', 'BTC', 'USDT', TRUE),
    ('ETH/USDT', 'ETH', 'USDT', TRUE);

INSERT INTO exchange_fee (exchange, taker_fee) VALUES
    ('BITGET', 0.001),
    ('KUCOIN', 0.001);
```

**`PollOrchestrationService.fetchTickersInParallel`** — previously looped `activePairs`
sequentially, fanning out only across adapters per symbol (2 symbols meant 2 sequential blocking
rounds). Now builds one flat list of `Mono<PriceTicker>` over the full symbol × adapter
cross-product (skipping unsupported combinations via `supports()`), and resolves it with a single
`Flux.fromIterable(...).flatMap(...).collectList().block()`. At 4 symbols × up to 5 venues each,
this is the difference between one round-trip per cycle and up to four.

### Backend: `MarketConfigValidator` — now a real invariant, not a no-op

The validator from the earlier Sprint 2 pass hardcoded `"USD"` for every symbol it saw (`// For
now, we hardcode USD since the config doesn't distinguish quote assets yet`) — it could structurally
never fire. Rewritten to read the actual `quoteAsset` per market from config and warn if one
internal symbol is configured with more than one quote asset across venues — a genuine config-typo
guard now that `quoteAsset` carries real, varying values.

### Frontend: `QuoteAssetService` and the toggle

**[quote-asset.service.ts](../frontend/src/app/services/quote-asset.service.ts)** — one signal
(`selected: string`, default `"USD"`), persisted to `localStorage` under a namespaced key, with
try/catch around storage access (private browsing can throw). Injected into `DashboardComponent`
and `ConnectionStatusComponent` — the two places that need to filter by it.

**`DashboardComponent`** — added a segmented toggle in the header, populated from
`config().quoteAssets` (itself now derived from distinct `tracked_pair.quote_currency` values via
`/api/config`, not hardcoded). Three computed signals filter the WebSocket snapshot by
`symbol.endsWith('/' + selectedQuote)`:

```typescript
private matchesSelectedQuote = (symbol: string) => symbol.endsWith('/' + this.quoteAsset.selected());

opportunities = computed(() => (this.websocket.snapshot()?.bestPerSymbol ?? []).filter(o => this.matchesSelectedQuote(o.symbol)));
matrix = computed(() => (this.websocket.snapshot()?.matrix ?? []).filter(o => this.matchesSelectedQuote(o.symbol)));

venueSummary = computed(() => {
    // Built from buyNativeSymbol/sellNativeSymbol actually present in the filtered matrix —
    // never a hardcoded string, so it's correct under either quote asset.
});
```

`SpreadDetailComponent` and `SpreadTableComponent` needed **no changes** for filtering — they
already receive `[opportunities]="opportunities()"` / `[matrix]="matrix()"` as inputs, so they
simply render whatever the dashboard already filtered. This was a deliberate design choice: one
filter point, not one per consuming component.

The hardcoded tooltip text from the earlier Sprint 2 pass (`"Each exchange is polled on its own
native market (Binance BTCUSD, Kraken XXBTZUSD, Coinbase BTC-USD)..."`) was removed from
`SpreadDetailComponent` and replaced by the dashboard-level `venueSummary()` tooltip — the old text
would have been actively wrong when viewing USDT.

### Frontend: `ConnectionStatusComponent` — per-quote badge and chip filtering

```typescript
const liveForSelectedQuote = snap.liveByQuote?.[selectedQuote] ?? snap.live;   // fallback for safety
// ... STALE (age > 10s) > DEGRADED (!liveForSelectedQuote) > LIVE, same precedence as before

visibleExchanges() {
  const selected = this.quoteAsset.selected();
  return (this.snapshot()?.exchanges ?? []).filter(ex => ex.offeredQuoteAssets?.includes(selected));
}
```

Switching the toggle to USD now removes Bitget and KuCoin's chips from the row entirely, rather
than rendering them as `NEVER` — the exact failure mode the "not-offered vs failed" distinction
(backend section above) exists to prevent from ever reaching the UI.

### Testing performed

**Backend** (`./gradlew test`, JDK 17 — this repo's toolchain requires 17+; the earlier build
blocker from `AppConfigDto`'s type mismatch was also fixed as part of this pass):

| Test class | What it covers |
|---|---|
| `BitgetAdapterTest` | success parse; HTTP-400-with-code-40034; HTTP-200-with-non-success-code (defensive); `supports()` false for `BTC/USD`; unsupported symbol short-circuits without a request |
| `KuCoinAdapterTest` | success parse; **HTTP-200-with-null-data resolves cleanly, doesn't NPE**; `supports()` false for `BTC/USD` |
| `KrakenAdapterTest` | rewritten against the new nested config shape; adds `supports()` and an error-array-inside-200 case |
| `SpreadCalculationServiceTest.testCrossQuoteAssetRoutesNeverMix` | given mixed USD/USDT tickers for the same base asset, no route pairs a USD leg with a USDT leg, and `bestPerSymbol` never crosses either |
| `ExchangeAvailabilityStoreTest` | per-(exchange, symbol) freshness is independent — the exact bug `liveByQuote` exists to prevent |

Result: 31/32 tests pass. The one failure (`SpreadLogRepositoryIntegrationTest`) requires a local
Docker daemon for Testcontainers, unavailable in the environment this was verified in — unrelated
to this change, pre-existing.

One bug caught by actually running the suite (not present before this pass): the prior
`SpreadCalculationServiceTest` screenshot test asserted `-0.3675%` for prices that, when run
through the real formula, produce `-0.3657%`. The `-0.3675%` figure came from a live screenshot at
a different instant than the fixed prices chosen for the test; fixed by asserting the value the
formula actually produces for the exact fixture inputs, with a comment explaining why the two
numbers differ.

**Frontend** — `ng build --configuration development`: zero errors. Initial run surfaced two
`NG8107` warnings (unnecessary `?.` now that `config()` is non-nullable via a `DEFAULT_CONFIG`
fallback); fixed by switching `config()?.fees` to `config().fees` in the two template spots. Final
build: zero errors, zero warnings.

### Ripple effects noted but not built this pass

- **Payload size**: routes per symbol are n×(n−1). USD stays at 6 routes/symbol (3 venues); USDT
  reaches 20 routes/symbol (5 venues). Across 4 symbols total that's roughly 52 matrix rows per
  snapshot vs. 12 before Bitget/KuCoin — proportionally larger WebSocket payloads every 3s. Not a
  problem at this scale; worth knowing before adding a third quote asset.
- **Layout**: the matrix component's group-by-symbol rendering (from the earlier Sprint 2 pass)
  already handles the row-count growth structurally; a wider sidebar or full-width matrix placement
  may read better with 5 venues visible under USDT than the original 3-venue layout, but this
  wasn't changed in this pass.

### Docs: "Fees and spread math" section

Add to README.md and ARCHITECTURE.md:

```markdown
## Fees and spread math

### Fee structure

Each exchange charges a taker fee on the notional value:

| Exchange  | Taker fee | Applied as |
|-----------|-----------|-----------|
| Binance   | 0.1%      | Fee on buy; deducted from sell proceeds |
| Kraken    | 0.26%     | Fee on buy; deducted from sell proceeds |
| Coinbase  | 0.6%      | Fee on buy; deducted from sell proceeds |

### Formula

For a route buying on exchange A at price P_buy and selling on exchange B at price P_sell:

```
Effective buy cost  = P_buy × (1 + feeA)
Effective sell proceeds = P_sell × (1 − feeB)

Net spread % = ((Effective sell proceeds / Effective buy cost) − 1) × 100
```

Example: Buy BTC/USD on Kraken at 64,967.30, sell on Binance at 64,963.00.

```
Effective buy cost = 64,967.30 × 1.0026 = 65,135.68
Effective sell proceeds = 64,963.00 × 0.999 = 64,899.04

Net spread = ((64,899.04 / 65,135.68) − 1) × 100 = −0.3675%
```

The negative spread means this route loses money after fees.
```

---

## Testing

### Backend

**SpreadPublisher unit test:**
- Mock `SimpMessagingTemplate`, call `publishSnapshot`, assert one message on `/topic/spreads`
- Test `live` flag transitions at the ≥2 fresh boundary
- Test serialization: `BigDecimal` → JSON number, `Instant` → ISO-8601 string

**Adapter tests (from Step 0.4):**
- All three pass, symbol mapping verified, error shapes confirmed

### Frontend

**websocket.service spec:**
- Mock STOMP client, emit snapshot, verify signal is updated
- Verify staleness timer fires after 10s without a message

**Profit calculation spec:**
- `(1000 * 0.5) / 100 = 5.00`
- `(1000 * -0.1) / 100 = -1.00`
- Zero notional edge case

---

## Exit checklist

- [ ] `./gradlew build` green (8/8 tests pass, Step 0.3 included)
- [ ] App starts on 8081, `./gradlew bootRun` logs "Tomcat started"
- [ ] All five REST endpoints live + return correct shapes
- [ ] Poll cycles run every ~3s, log shows "Poll cycle completed"
- [ ] `ng serve` starts on 4200, proxies to backend
- [ ] Open `localhost:4200` — dashboard renders without errors
- [ ] LIVE badge green when backend is running, red after 10s with no WebSocket message
- [ ] Per-exchange chips show FRESH (≥2 in 10s) or STALE/NEVER
- [ ] Best opportunity cards render with buy/sell venues and profit estimate
- [ ] Full matrix table shows all routes, colored by net spread sign (green + / red −)
- [ ] Change notional input → profit recalculates without HTTP call
- [ ] Stop backend → badge flips to red after ~10s (staleness timer)
- [ ] Verify no HTTP polling in Network tab, only WebSocket

---

## Known issues / gotchas

1. **sockjs-client needs `global` polyfill** under Angular's browser build. Add `(window as any).global = window;` to `main.ts` before bootstrap or it throws `global is not defined` at runtime.

2. **STOMP callbacks fire outside Angular zone.** Signal writes from the callback may not trigger change detection with zone-based detection. Wrap writes in `NgZone.run()` (done above) or switch to zoneless mode (`provideExperimentalZonelessChangeDetection` in app.config).

3. **One timestamp per cycle is new.** Persisted history rows now have the same `calculated_at` if they're from the same cycle. This is correct, but if you query history and group by `calculated_at`, the count of rows per timestamp will differ from the matrix size (best-per-symbol is ≤ matrix).

---

## Out of scope (Sprint 3)

Docker images, Nginx + SPA routing, production cert handling, external fee config, advanced error recovery (429 backoff, circuit breakers), notifications.
