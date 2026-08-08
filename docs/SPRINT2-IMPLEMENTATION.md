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
