# Sprint 2 Plan — Realtime + Dashboard

**Goal:** Push the full matrix live and render a clear monitoring UI.

**Exit criteria (from [SPRINT.md](./SPRINT.md)):** With the backend from Sprint 1, the dashboard
updates live without frontend polling and correctly reflects partial exchange outages and negative
best spreads.

---

## Step 0 — Close the open Sprint 1 items (prerequisite)

Sprint 2's exit criteria begin "With backend from Sprint 1…". That backend has never run, so these
come first. Nothing in Sprint 2 can be demonstrated until they are green.

| # | Item | Fix |
|---|---|---|
| 0.1 | App can't start — port 8080 held by `com.docker.backend.exe` | Move to `server.port=8081`; update README, proxy config, Sprint 3 Compose mapping |
| 0.2 | `./gradlew build` red — Testcontainers can't find Docker | Write `~/.testcontainers.properties` pointing at `npipe:////./pipe/dockerDesktopLinuxEngine` |
| 0.3 | Integration test never executed | Run it; confirm Flyway + seeds + repository round-trip inside the container |
| 0.4 | No adapter tests exist | Write three, using a stubbed `ExchangeFunction` (see 0.4 note below) |
| 0.5 | No live smoke test | Start app, confirm cycles run against real APIs, curl all five endpoints |

**0.4 note:** the deleted `BinanceAdapterTest` failed because Boot 4 renamed the reactive client
surface — `ClientRequest.getURI()` is now `url()`, and `ExchangeStrategies.decoders()` is gone. The
replacement builds responses with `ClientResponse.create(HttpStatus.OK).header(CONTENT_TYPE,
APPLICATION_JSON).body(fixtureJson).build()` inside the stub function, asserting `request.url()` for
symbol mapping. Fixtures go in `src/test/resources/fixtures/{binance,kraken,coinbase}/`, captured
from the live probes already run (Binance `bookTicker`, Kraken `XXBTZUSD` result keys, Coinbase
`/products/BTC-USD/ticker`). One error-shape test each: Kraken's `error[]` inside a 200, an HTTP 429,
and a missing-field payload.

**0.5 verification** — the gate for starting Sprint 2 proper:

```bash
curl -s localhost:8081/api/pairs | jq
curl -s localhost:8081/api/exchanges | jq
curl -s localhost:8081/api/fees | jq
sleep 6
curl -s localhost:8081/api/spreads/latest | jq
curl -s "localhost:8081/api/spreads/history?limit=10" | jq
curl -s -o /dev/null -w "%{http_code}\n" localhost:8081/api/spreads/history   # expect 400
```

---

## Backend work

### 1. Cycle timestamp threading (do before publishing)

Today `SpreadDto.from(SpreadOpportunity)` stamps `Instant.now()` per row, so rows from one cycle get
different timestamps. Once a whole cycle is published as one snapshot that becomes visibly wrong —
the UI's "last update" would differ per row.

Add a single `calculatedAt` to `CalculationResult`, set once per cycle, and use it for both the
persisted `SpreadLog` and the published payload.

### 2. Fix cycle parallelism

`PollOrchestrationService.fetchTickersInParallel` loops symbols **sequentially** and only fans out
across exchanges — 2 blocking round-trips per cycle, not 1. `ARCHITECTURE.md:94-99` calls for the
whole cycle to run in parallel. Flatten to one `Flux` over the symbol × adapter cross-product,
collect once, then group by symbol.

### 3. WebSocket / STOMP / SockJS (`config/WebSocketConfig.java`)

- `@EnableWebSocketMessageBroker`
- STOMP endpoint `/ws`, `.withSockJS()` for transport fallback
- `enableSimpleBroker("/topic")` — in-memory broker is sufficient for V1
- `setAllowedOriginPatterns` driven by config, not hardcoded

### 4. Publish one envelope per cycle

**Decision (assumed):** a **single message on `/topic/spreads`** carrying matrix + status + timestamp
together, rather than a separate `/topic/status`. One cycle produces one consistent snapshot, so the
table, the best-opportunity cards, and the LIVE badge can never disagree, and the client manages one
subscription.

```
SpreadSnapshotDto
├── calculatedAt        Instant — one per cycle
├── matrix              SpreadDto[]        full directed matrix, all symbols
├── bestPerSymbol       SpreadDto[]        server-picked highest net per symbol (may be negative)
├── exchanges           ExchangeStatusDto[]  name, available, lastUpdate, freshness
├── freshExchangeCount  int
└── live                boolean            freshExchangeCount >= 2 within the 10s window
```

`live` is computed server-side so the "≥2 fresh within 10s" rule lives in exactly one place. The
client still needs its own staleness timer for the different failure of *no messages arriving at all*
— see frontend item 3.

New `SpreadPublisher` service holding `SimpMessagingTemplate`, called from `PollOrchestrationService`
immediately after `snapshotStore.update(...)`. Publishing must not be inside the DB transaction, and
a DB write failure must not suppress the publish (`ARCHITECTURE.md:202`).

### 5. Housekeeping in code written during Sprint 1

- `PollOrchestrationService` carries two loggers (`log` and a trailing static `logger`) because a
  local `SpreadLog log` shadows the field inside `persistBestOpportunities`. Rename the local, drop
  the duplicate.
- `FeeService.getFeeForExchange` has a dead `if (config != null) { }` block with only a comment in it.
- `FeeService` hits the DB once per exchange per cycle (~20 queries/minute for static data). Cache
  with a scheduled refresh, keeping the DB as source of truth.

---

## Frontend work

Scaffold already has what's needed: Angular 19 standalone, `@stomp/stompjs` 7.3.0, `sockjs-client`
1.6.1, `provideHttpClient`, Tailwind.

### 1. Dev wiring — `proxy.conf.json`

**Decision (assumed):** frontend calls same-origin `/api` and `/ws`; `ng serve` proxies to
`localhost:8081` (`/ws` with `ws: true`). This is what Nginx will do in Sprint 3, so no URL changes
and no `environment.ts` swap when containerizing, and no CORS configuration at all.

### 2. Models (`src/app/models/`)

`spread.model.ts`, `exchange.model.ts`, `snapshot.model.ts` — mirror the DTOs above. Prices and
percents arrive as JSON numbers from `BigDecimal`; keep them `number` in TS and format at the edge.

### 3. `services/websocket.service.ts`

- `@stomp/stompjs` `Client` with `webSocketFactory: () => new SockJS('/ws')`
- Exposes `snapshot = signal<SpreadSnapshotDto | null>(null)` and
  `connection = signal<'connecting' | 'open' | 'closed'>(...)`
- Automatic reconnect with backoff
- **Client-side staleness timer:** if no message arrives for > 10s, the badge drops out of LIVE even
  though the last envelope said `live: true`. Server freshness and transport health are different
  failures and the UI must reflect both.

Two known gotchas:

- `sockjs-client` references `global`, which Angular's browser build does not define. Needs
  `(window as any).global = window;` in `polyfills` (or an equivalent `define`) or it fails at
  runtime with `global is not defined`.
- STOMP callbacks fire outside the Angular zone. With zone-based change detection (`app.config.ts`
  currently uses `provideZoneChangeDetection`), signal writes from the callback may not schedule a
  render. Either write via `NgZone.run` or move to zoneless. Verify early — this is the classic
  "data arrives but the UI never repaints" bug.

### 4. `services/spread.service.ts` + `services/exchange.service.ts`

REST bootstrap only — `/api/pairs`, `/api/spreads/latest`, `/api/spreads/history`, `/api/exchanges`,
`/api/fees`. Used to paint the first frame before the first WebSocket envelope lands. **No polling
loop** — the sprint explicitly forbids it.

### 5. Components (`src/app/components/`)

| Component | Responsibility |
|---|---|
| `dashboard/` | Container; owns the notional signal; composes the rest |
| `spread-detail/` | Best opportunity per symbol — buy venue + ask, sell venue + bid, raw %, net %, estimated profit. Renders negative spreads plainly, styled as loss rather than hidden |
| `spread-table/` | Full matrix from the live topic |
| `connection-status/` | LIVE/degraded badge, per-exchange chips (FRESH/STALE/NEVER), last update time |

### 6. Notional and estimated profit

Selectable input, default `$1,000`. Profit is derived client-side and never persisted.

Because net spread % is already the return on effective buy cost, the display math is exactly:

```
estimatedProfit = notional × netSpreadPercent / 100
```

No need to re-derive units or re-apply fees in the UI — that would risk drifting from the server's
number. Show the fee assumption alongside it so the estimate is legible.

### 7. Copy

UI says **indicative cross-venue arbitrage opportunity**. Buy/sell direction must be unambiguous
("Buy on Kraken @ ask 64,943 → Sell on Coinbase @ bid 65,010"). A visible note that depth, slippage,
withdrawal fees, and transfer time are not modelled.

---

## Testing

**Backend**
- `SpreadPublisher` unit test with a mocked `SimpMessagingTemplate` — asserts one message per cycle
  on `/topic/spreads` and that `live` follows the ≥2-fresh-in-10s rule (fresh/stale/never boundary cases)
- Snapshot serialization test — `BigDecimal` renders as JSON numbers, `Instant` as ISO-8601
- Carry-over adapter tests from step 0.4

**Frontend**
- Profit calculation spec (positive, negative, zero notional)
- Freshness/LIVE derivation spec, including the transport-stale case
- A `websocket.service` spec against a fake STOMP client — no real socket

---

## Exit checklist

- [ ] Step 0 complete: `./gradlew build` green, app runs, all five endpoints verified live
- [ ] STOMP endpoint `/ws` accepts SockJS connections
- [ ] One envelope published per cycle on `/topic/spreads`
- [ ] Dashboard renders best opportunity per symbol, negatives included
- [ ] Matrix table updates live, no HTTP polling in the network tab
- [ ] LIVE badge green only when ≥2 exchanges fresh within 10s
- [ ] Killing one adapter (bad base URL) degrades to 2 venues without stalling the cycle
- [ ] Killing the backend flips the badge out of LIVE via the client staleness timer
- [ ] Notional change updates estimated profit without a round-trip
- [ ] Copy uses "indicative cross-venue arbitrage opportunity"
- [ ] Last-update timestamp and buy/sell direction visible

---

## Assumptions taken (unanswered questions — flip cheaply if wrong)

| Assumed | Alternative |
|---|---|
| Backend moves to **8081** | Free 8080, or make it env-driven with 8080 default |
| **Single envelope** on `/topic/spreads` | Separate `/topic/spreads` + `/topic/status` |
| **Angular proxy** for `/api` + `/ws` | Absolute URLs + CORS and WebSocket allowed-origins |
| **`~/.testcontainers.properties`** for the Docker path | `DOCKER_HOST` in `build.gradle`, or README-only |

The first three are one-line config changes plus a doc edit. The fourth changes nothing in the
application itself.

---

## Out of scope (Sprint 3)

Docker images for backend/frontend, Nginx SPA + proxy routing, 429 backoff, externalized production
config, README quick start from clean checkout.
