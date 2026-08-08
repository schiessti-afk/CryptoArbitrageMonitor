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

## Sprint 2 Refinements — UI clarity and fee transparency

After initial delivery, seven improvements to make the dashboard honest, transparent, and testable.

### 1. Make quote market explicit and self-verifying

Add `nativeSymbol` and `quoteAsset` to [PriceTicker](../../backend/src/main/java/com/cryptoarbitrage/monitor/exchange/PriceTicker.java) and thread through to `SpreadDto`, enabling per-leg asset visibility. Nest symbol config in `ExchangeProperties`:

```
exchange.binance.markets.BTC_USD.native-symbol=BTCUSD
exchange.binance.markets.BTC_USD.quote-asset=USD
```

Add `@PostConstruct` config validator that logs WARN if configured quote assets disagree for one internal symbol — catches accidental USDT swaps. Update UI header to state explicitly: `BTC/USD — indicative cross-venue comparison` with tooltip listing native markets (Binance BTCUSD, Kraken XXBTZUSD, Coinbase BTC-USD).

### 2. "Best Current Spreads" with explicit verdict

Rename heading from "Best Opportunities" to "Best Current Spreads". Add per-card status band driven by a shared classifier in `frontend/src/app/utils/spread-state.ts`:

| State | Condition | Display |
|---|---|---|
| POTENTIAL OPPORTUNITY | net > +0.001% | 🟢 `Net spread: +0.2910%` |
| NO POSITIVE OPPORTUNITY | net < −0.001% | 🔴 `Best net spread: -0.3675%` |
| NO MEANINGFUL SPREAD | \|net\| ≤ 0.001% | ⚪ `Net spread: 0.0000%` |

Keep `Est. Profit` labelled **Estimated profit** and allow it to go negative — that's the honest reading.

### 3. Unified spread-state styling

Replace all `netSpreadPercent < 0 ? ... : ...` ternaries in the detail card and table with a shared `stateClasses()` map that returns Tailwind classes for background, border, dot, and text. Eliminates duplication and ensures the two views classify identically.

### 4. Matrix: group by symbol, then sort by net desc

Add a symbol column (or grouped subheaders). Sort within each symbol by `netSpreadPercent` descending (tiebreak on raw desc, then buy exchange name). Group the computed signal in [spread-table.component.ts](../../frontend/src/app/components/spread-table/spread-table.component.ts) so the WebSocket payload stays flat. Also standardize matrix precision to 4 decimals to match the detail card (currently 2dp in the table, 4dp in the card).

### 5. Notional quick-select + config endpoint

Add quick-select buttons `$100 / $1,000 / $5,000 / $10,000 / $50,000` above the existing input. Clamp input (min 1, max 10,000,000). Expose `GET /api/config` returning `defaultNotional`, `freshnessWindowMs`, `neutralEpsilonPercent`, and the live fee list, so the frontend has one source of truth.

### 6. Ticking freshness, measured on client clock

Fix three problems:
- Add a 1s `setInterval` in `ConnectionStatusComponent` (registered in `NgZone`, cleared in `ngOnDestroy`) driving a `now` signal for age recalculation.
- Record `receivedAt = Date.now()` when a message arrives, measure age from that (not from server's `calculatedAt`).
- Remove the `snapshot.live = false` mutation on the staleness timer; instead expose `lastMessageAt` and let the UI derive the three-state badge (LIVE / DEGRADED / STALE) with logic: STALE if age > 10s, else DEGRADED if backend `live=false`, else LIVE.

### 7. Fee visibility and reproducibility

Add `GET /api/config` endpoint. Render a collapsible **Fees & spread math** panel showing the fee list and formula:

```
net% = ((sell × (1 − sellFee)) / (buy × (1 + buyFee)) − 1) × 100
```

Add an optional `Fees %` column to the matrix showing the fee impact per route. Write a `SpreadCalculationServiceTest` case using prices in the same range as the reported screenshot (buy 64,967.30 Kraken / sell 64,963.00 Binance, fees 0.26%/0.1%) and assert the value the formula actually produces for those exact inputs (**−0.3657%**, independently computed) — not the screenshot's −0.3675%, which was read off a live UI at a different instant and isn't reproducible bit-for-bit from a fixed fixture. The point of the test is that the *formula* is verifiable from whatever numbers the UI displays, not that one frozen fixture matches one screenshot. Add test cases for `spreadState()` boundaries and matrix sort order. Update README and [ARCHITECTURE.md](../ARCHITECTURE.md) with a "Fees and spread math" section, fee table, formula, and one worked example.

---

## Sprint 2 Refinements — Bitget, KuCoin, and the USD/USDT toggle

Bitget and KuCoin only list BTC/ETH against **USDT** — verified live: Bitget's `BTCUSD` symbol
returns HTTP 400 (`"Parameter BTCUSD does not exist"`); KuCoin's `BTC-USD` returns HTTP 200 with a
null `data` payload. Rather than mixing USD and USDT prices in one comparison (which would bake an
unstated FX assumption into every net spread), USDT is modeled as its own quote-asset universe with
its own venue set — `BTC/USDT` and `ETH/USDT` as new `tracked_pair` rows, never blended with the
USD pairs. A frontend toggle switches between the two universes; each shows only the venues that
actually offer it.

Verified availability matrix (probed against live public APIs):

| Venue | BTC/USD | ETH/USD | BTC/USDT | ETH/USDT |
|---|---|---|---|---|
| Binance | ✓ `BTCUSD` | ✓ `ETHUSD` | ✓ `BTCUSDT` | ✓ `ETHUSDT` |
| Kraken | ✓ `XXBTZUSD` | ✓ `XETHZUSD` | ✓ `XBTUSDT`* | ✓ `ETHUSDT` |
| Coinbase | ✓ `BTC-USD` | ✓ `ETH-USD` | ✓ `BTC-USDT` | ✓ `ETH-USDT` |
| Bitget | — | — | ✓ `BTCUSDT` | ✓ `ETHUSDT` |
| KuCoin | — | — | ✓ `BTC-USDT` | ✓ `ETH-USDT` |

\* Kraken's USDT symbols don't follow its USD `X../Z..` wrapping convention, and the response is
keyed by the native symbol requested (`XBTUSDT`, not `BTCUSDT`).

### 1. Config: nested per-venue markets, not a flat symbol map

`ExchangeProperties` moves from three hardcoded fields (`binance`/`kraken`/`coinbase`, each
duplicated across field/getter/`getAdapters()`) to one Spring-bound `Map<String, ExchangeConfig>
adapters`, keyed by lowercase venue name. Each `ExchangeConfig` carries a `Map<String,
MarketConfig> markets`, keyed by internal symbol (`BTC_USD`, `BTC_USDT`, …), each holding
`nativeSymbol` and `quoteAsset`. A venue's absence of an entry for a market **is** the fact that it
doesn't offer that market — this single source of truth drives `ExchangeAdapter#supports`,
`MarketConfigValidator`, and the frontend's venue-chip filtering, so adding or removing a market
for a venue is a config-only change.

`WebClientConfig` keeps five explicit `@Bean` methods (one per venue) rather than registering
`WebClient` beans dynamically from the map — dynamic singleton registration from a `@PostConstruct`
risks a bean-creation-order race against adapters injecting via `@Qualifier`, and five one-line
methods reading from the map by key carry none of that risk.

### 2. Not-offered vs failed: `ExchangeAdapter#supports`

Before this could be built safely, the interface needed a way to distinguish "this venue doesn't
list this market" from "this venue failed to respond." `ExchangeAdapter` gains:

```java
boolean supports(String internalSymbol);
```

backed by presence in the venue's `markets` config. `PollOrchestrationService` checks this before
issuing any request — an unlisted market produces no HTTP call, no availability-store update, and
no `WARN` log. Without this, polling Bitget for `BTC/USD` every 3 seconds would either warn forever
or make Bitget render as permanently `NEVER` in the UI, indistinguishable from an actual outage.

### 3. Freshness becomes per-(exchange, symbol)

`ExchangeAvailabilityStore`'s key widens from `Exchange` to `(Exchange, symbol)` — a venue can be
healthy on one market and failing on another. Per-exchange convenience methods
(`isFreshAny`, `getLastReceivedAtAny`) aggregate across whatever symbols that exchange has actually
reported for, used for the venue-level "is this thing alive at all" chip. Per-quote-asset LIVE
computation uses the per-symbol methods directly: `SpreadPublisher` groups active `tracked_pair`
rows by `quoteCurrency` (the authoritative source — not inferred from live ticker data) and
publishes `liveByQuote` / `freshCountByQuote` maps alongside the existing global `live` field. This
is the fix for the scenario the global count couldn't express: all three USD venues down while
Bitget and KuCoin (USDT-only) are healthy would previously read as globally LIVE (`freshCount ≥
2`) while the USD view a user is looking at is empty.

### 4. Two new adapters, two real error-shape gotchas

`BitgetAdapter` — `GET /api/v2/spot/market/tickers?symbol={native}`. Bid/ask at `data[0].bidPr` /
`data[0].askPr` — `data` is an **array**, unlike every existing adapter. An unknown symbol returns
HTTP 400 *and* envelope code `"40034"`; the code check is the authoritative one, since a future
error path could plausibly return 200 with a non-success code.

`KuCoinAdapter` — `GET /api/v1/market/orderbook/level1?symbol={native}`. Bid/ask at
`data.bestBid` / `data.bestAsk`. **The gotcha:** an unknown symbol returns **HTTP 200** with
`{"code":"200000","data":null}` — the standard `onStatus` non-2xx check cannot catch this at all.
`parseTicker` must explicitly check for a null `data` node before touching `data.bestBid`, or a
`NullNode` field access throws an unguarded NPE instead of resolving to a clean adapter failure
(caught by a unit test — see Testing below).

Both set `quoteAsset = "USDT"` on their `PriceTicker`s — the field added earlier in Sprint 2
finally carries a value other than `"USD"`.

### 5. `Exchange` enum, migration, poll-cycle flattening

`Exchange` gains `BITGET`, `KUCOIN`. `V3__add_usdt_universe.sql` seeds the two new
`tracked_pair` rows (`BTC/USDT`, `ETH/USDT`) and two `exchange_fee` rows (0.1% base-tier spot
taker for each, flagged as configurable estimates like the V1 seed data — worth checking against
current published schedules before treating as accurate long-term).

`PollOrchestrationService.fetchTickersInParallel` — previously looped symbols sequentially,
fanning out only across adapters (one blocking round-trip per symbol). Now flattened into a single
`Flux` over the full symbol × adapter cross-product, filtered by `supports()` before any request is
built. At 2 symbols × 3 venues this was optional; at 4 symbols × 5 venues (with per-symbol
participation varying) it's the difference between one round-trip per cycle and up to four.

### 6. Frontend: the toggle and what it filters

`QuoteAssetService` holds one signal (`selected: 'USD' | 'USDT'`), persisted to `localStorage`, and
is injected wherever quote-asset-aware filtering happens. `DashboardComponent` derives three
computed signals from the same WebSocket snapshot — `opportunities`, `matrix`, and a data-driven
`venueSummary` for the header tooltip — all filtered by `symbol.endsWith('/' + selectedQuote)`. The
matrix and detail-card components are unchanged; they simply receive an already-filtered `[matrix]`
/ `[opportunities]` input, so no duplicate filtering logic exists across components.

`ConnectionStatusComponent` reads `liveByQuote[selected]` instead of the global `live` flag for its
badge, and filters exchange chips to only those where `ex.offeredQuoteAssets.includes(selected)` —
so Bitget and KuCoin simply don't render as chips under USD, rather than rendering as broken.

The header's info tooltip is now built from the native symbols actually present in the current
(filtered) matrix (`venueSummary()`), rather than a hardcoded string — under USDT it lists Bitget
and KuCoin's native symbols instead of silently repeating the USD-era Binance/Kraken/Coinbase
listing.

### 7. What stayed exactly as designed in the initial refinements

Card/matrix spread-state styling ([Section 2–3](#2-best-current-spreads-with-explicit-verdict)),
matrix grouping and sort order (Section 4), notional quick-select (Section 5), and the client-side
freshness ticker (Section 6) are all unchanged in mechanism — they simply now operate on the
filtered, per-quote data instead of an unfiltered global snapshot.

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

**Backend — Bitget/KuCoin/toggle refinements** (all green as of this writing; see
[SPRINT2-IMPLEMENTATION.md](./SPRINT2-IMPLEMENTATION.md) for the actual `./gradlew test` run)
- `BitgetAdapterTest` — success parse, HTTP-400-with-error-code, HTTP-200-with-error-code
  (defensive: code check independent of status), `supports()` false for `BTC/USD`
- `KuCoinAdapterTest` — success parse, and the case that matters most: HTTP 200 with
  `data: null` resolves to an empty `Mono` rather than throwing an NPE
- `KrakenAdapterTest` rewritten against the new nested `markets` config shape; adds a
  `supports()` case and an error-array-inside-200 case that was previously untested
- `SpreadCalculationServiceTest#testCrossQuoteAssetRoutesNeverMix` — the core invariant: given
  mixed USD and USDT tickers for the same base asset, no route ever pairs a USD leg with a USDT
  leg, and `bestPerSymbol` never crosses either
- `ExchangeAvailabilityStoreTest` — asserts per-(exchange, symbol) freshness is independent
  across quote-asset universes (the exact scenario `liveByQuote` exists to get right)

**Frontend — Bitget/KuCoin/toggle refinements**
- Build verified clean (`ng build`, zero errors, zero warnings after minor template cleanup)
- Manual verification path: toggle to USDT, confirm Bitget/KuCoin chips appear and Binance/
  Kraken/Coinbase chips remain (all five list USDT); toggle to USD, confirm Bitget/KuCoin chips
  disappear entirely rather than showing as STALE/NEVER

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
- [x] Bitget and KuCoin poll BTC/USDT and ETH/USDT successfully
- [x] Bitget/KuCoin never polled for BTC/USD or ETH/USD, never logged as failing for it
- [x] USD ⇄ USDT toggle filters cards, matrix, and chips consistently from one snapshot
- [x] `liveByQuote` reflects per-quote-asset health independently (verified by unit test)
- [x] No route in any published matrix ever mixes a USD leg with a USDT leg (verified by unit test)

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
