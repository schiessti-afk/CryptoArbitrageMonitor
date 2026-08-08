# Sprint 1 Implementation Plan

**Goal:** Poll exchanges, compute spreads, persist best opportunities, expose REST bootstrap APIs.

**Exit criteria:** Backend runs against Postgres, completes poll cycles with ≥1 exchange, writes best rows, serves REST without frontend.

---

## Implementation Order

### 1. Schema & migrations (`backend/src/main/resources/db/migration/`)

**V1__init_schema.sql**
- `tracked_pair` (id, symbol, base_currency, quote_currency, active, created_at)
  - Seed: BTC/USD, ETH/USD (active)
- `exchange_fee` (id, exchange, taker_fee, updated_at)
  - Seed: one row per exchange (Binance, Kraken, Coinbase) with configurable estimates
- `spread_log` (id, symbol, buy_exchange, sell_exchange, buy_price, sell_price, raw_spread_percent, net_spread_percent, calculated_at)
  - Numeric precision: prices `numeric(20,8)`, percents `numeric(12,6)`, timestamps `timestamptz`

**V2__spread_log_indexes.sql**
- Index on `(symbol, calculated_at desc)` for `/api/spreads/latest` and `/api/spreads/history` queries

### 2. Model classes & repositories (`backend/src/main/java/com/cryptoarbitrage/monitor/model/`, `repository/`)

**Entities:**
- `TrackedPair` (JPA entity)
- `ExchangeFee` (JPA entity)
- `SpreadLog` (JPA entity, maps to spread_log table)

**Repositories:**
- `TrackedPairRepository`
- `ExchangeFeeRepository`
- `SpreadLogRepository` with derived queries:
  - Latest per symbol (order by calculated_at desc, limit 1 per symbol)
  - Bounded history (limit + from/to filters)

### 3. Exchange layer (`backend/src/main/java/com/cryptoarbitrage/monitor/exchange/`)

**Core interface & types:**
- `ExchangeAdapter` interface: `Mono<PriceTicker> getTicker(String internalSymbol)`
- `Exchange` enum: BINANCE, KRAKEN, COINBASE
- `PriceTicker` record/class: exchange, symbol, bid, ask, receivedAt (Instant, UTC)

**Adapters (public APIs only):**
- `BinanceAdapter`
  - Endpoint: `/api/v3/ticker/bookTicker?symbol={BTCUSD,ETHUSD}`
  - Native mapping: BTC/USD → BTCUSD, ETH/USD → ETHUSD
  - Error handling: HTTP errors, missing fields
- `KrakenAdapter`
  - Endpoint: `/0/public/Ticker?pair={XXBTZUSD,XETHZUSD}`
  - Native mapping: BTC/USD → XXBTZUSD, ETH/USD → XETHZUSD
  - Error handling: HTTP 200 with error array, response key mapping
- `CoinbaseAdapter`
  - Endpoint: `/products/{BTC-USD,ETH-USD}/ticker` (per-product, no batch)
  - Native mapping: BTC/USD → BTC-USD, ETH/USD → ETH-USD
  - Error handling: HTTP errors

**Behavior:**
- Each adapter maps its own native symbols to internal format (BTC/USD, ETH/USD)
- Native symbol mapping lives in `@ConfigurationProperties` (ExchangeProperties)
- On error, return `Mono.empty()` so the cycle continues with other exchanges
- Parse only bid, ask, timestamp; ignore everything else

### 4. Configuration (`backend/src/main/java/com/cryptoarbitrage/monitor/config/`)

**Classes:**
- `WebClientConfig`: per-exchange WebClient beans with connect/response timeouts (e.g. 5s)
- `ExchangeProperties` (@ConfigurationProperties): base URLs, symbol maps per exchange, timeouts
- `AppProperties`: poll interval (3000ms), freshness window (10000ms), default notional ($1000)
- `SchedulingConfig`: @EnableScheduling, scheduler executor pool

**Property file (`application.properties`):**
```properties
# Exchange URLs
exchange.binance.base-url=https://api.binance.com
exchange.kraken.base-url=https://api.kraken.com
exchange.coinbase.base-url=https://api.exchange.coinbase.com

# Symbol mappings (exchange → internal)
exchange.binance.symbols.BTC/USD=BTCUSD
exchange.binance.symbols.ETH/USD=ETHUSD
# ... etc for Kraken, Coinbase

# Polling
app.polling.interval-ms=3000
app.polling.freshness-window-ms=10000
app.investment.default-notional=1000
```

### 5. Core spread engine (`backend/src/main/java/com/cryptoarbitrage/monitor/service/SpreadCalculationService.java`)

**Pure, testable service:**
- Input: Map of symbol → List<PriceTicker> (multiple exchanges' bids/asks)
- Input: Map of exchange → taker fee
- Logic:
  - For each symbol:
    - Build full directed matrix: all pairs (buy_exchange ≠ sell_exchange)
    - For each route: buy_price = ask on buy venue, sell_price = bid on sell venue
    - Raw spread: `((sell_price / buy_price) - 1) × 100`
    - Effective buy cost: `buy_price × (1 + buy_fee)`
    - Effective sell revenue: `sell_price × (1 - sell_fee)`
    - Net spread: `(sell_revenue / buy_cost - 1) × 100`
  - Return: full matrix + best route per symbol (highest net spread, including negatives)
- No repository calls, no timing — pure math, unit-testable

### 6. Poll orchestration & persistence (`backend/src/main/java/com/cryptoarbitrage/monitor/service/PollOrchestrationService.java`)

**Scheduled, non-overlapping cycles:**
- `@Scheduled(fixedDelay = 3000)` on a method guarded by `AtomicBoolean` in-flight flag
- Parallel WebClient fetches: `Mono.zip()` or `Mono.zipDelayError()` to continue on partial failure
- Filter tickers: drop any with missing/non-positive bid or ask
- Record availability: store last receivedAt per exchange globally
- Invoke `SpreadCalculationService` with available tickers + fees
- Persist best opportunity per symbol using `SpreadLogRepository.save()`
- Store full matrix in in-memory `MarketSnapshotStore` for Sprint 2 STOMP publishing

**MarketSnapshotStore (simple holder):**
- Holds the latest full matrix + timestamp
- Thread-safe (volatile or AtomicReference)
- Fetched by `/api/spreads` endpoint and Sprint 2 STOMP publisher

### 7. REST API (`backend/src/main/java/com/cryptoarbitrage/monitor/controller/`, `dto/`)

**Endpoints:**

| Method | Path | Response |
|---|---|---|
| GET | `/api/pairs` | `[{ symbol, baseCurrency, quoteCurrency, active, createdAt }]` |
| GET | `/api/exchanges` | `[{ exchange, available, lastUpdate, freshness }]` |
| GET | `/api/fees` | `[{ exchange, takerFee, updatedAt }]` |
| GET | `/api/spreads/latest` | `[{ symbol, buyExchange, sellExchange, buyPrice, sellPrice, rawSpreadPercent, netSpreadPercent, calculatedAt }]` |
| GET | `/api/spreads/history?limit=100&from=2026-08-08&to=2026-08-09` | Paginated array (same fields as latest) |

**Validation:**
- `/api/spreads/history` requires `limit` (1..1000), optional `from`/`to` (ISO 8601 instant or date)
- Unbounded queries (missing limit) rejected with 400

**Error handling:**
- `@RestControllerAdvice` with `@ExceptionHandler`
- Consistent error body: `{ error, timestamp, path }`

### 8. Unit tests

**SpreadCalculationService:**
- Positive spread (profitable route)
- Negative spread (loss on all routes)
- Zero spread (equal prices)
- Fee impact on net (high fee kills profitability)
- Asymmetric fees (different buy/sell venue fees)
- Sub-basis-point spreads
- Invalid inputs (zero/negative prices, nulls)

**Adapter tests:**
- Binance: fixture → parse → verify symbol mapping + bid/ask + timestamp
- Kraken: fixture (with error cases) → parse → verify response key mapping (XXBTZUSD response comes from BTC/USD request)
- Coinbase: fixture → parse → verify conversion
- One error test per adapter: Kraken's error array, HTTP timeout/429, missing fields

### 9. Integration test

**Spring Boot + Testcontainers Postgres:**
- Spin up a real Postgres container (Testcontainers library)
- Flyway runs migrations automatically
- Seeds (tracked_pair, exchange_fee) are in place
- Insert a test SpreadLog row
- Query latest per symbol via `SpreadLogRepository`
- Verify result matches inserted row

---

## Key decisions locked in

| Topic | Decision | Rationale |
|---|---|---|
| `spread_log.estimated_profit` | **Omitted** | Store prices + spreads; UI derives profit from selectable notional. |
| Fee truth | **`exchange_fee` table + `FeeService` cache** | Runtime-configurable, survives restart, fallback to properties if row missing. |
| `/api/spreads/latest` source | **Read from DB** | Survives restart before first cycle completes. |
| History unbounded query | **Rejected (400)** | Architecture demands bounded queries; no "give me all" route. |
| Live matrix storage | **In-memory `MarketSnapshotStore`** | Seam for Sprint 2 STOMP publisher. |
| Exchange type | **Java enum** | No exchange table; health stored in memory. |

---

## Testing checklist

- [ ] Spread math: raw/net, fees, edge cases
- [ ] Adapter parsing + symbol mapping
- [ ] Adapter error handling (per-exchange shapes)
- [ ] Repository: round-trip via Testcontainers Postgres
- [ ] REST endpoints (happy path + error bounds)
- [ ] In-flight guard: scheduled cycle doesn't overlap

---

## Exit criteria checklist

- [ ] `docker compose up -d postgres` → Postgres healthy
- [ ] `cd backend && ./gradlew clean build` → compiles, tests pass
- [ ] `./gradlew bootRun` → Spring starts, Flyway applies migrations, seeds load
- [ ] Curl `/api/pairs` → returns BTC/USD, ETH/USD
- [ ] Curl `/api/exchanges` → returns BINANCE, KRAKEN, COINBASE
- [ ] Curl `/api/fees` → returns one taker fee per exchange
- [ ] Wait 6 seconds
- [ ] Curl `/api/spreads/latest` → returns best opportunity per symbol (raw + net)
- [ ] Curl `/api/spreads/history?limit=10` → returns last 10 best rows, ordered by time
- [ ] Curl `/api/spreads/history` (no limit) → 400 error
- [ ] `./gradlew test` → all unit + integration tests pass
- [ ] Backend logs show poll cycles every 3s, no overlaps (in-flight guard active)

---

## Not in Sprint 1

- WebSocket/STOMP publishing (Sprint 2)
- Frontend changes (Sprint 2)
- Docker backend/frontend images (Sprint 3)
- Nginx (Sprint 3)
- Advanced error recovery / 429 backoff refinement (Sprint 3)
