# Sprint 1 Implementation Summary

**Status:** COMPLETE ✅

---

## Overview

Sprint 1 implementation delivers the core backend for polling exchanges, calculating spreads, persisting opportunities, and exposing REST APIs. The backend runs against PostgreSQL, completes non-overlapping poll cycles, writes best opportunities to the database, and serves REST without the frontend.

---

## Deliverables Completed

### 1. Database Schema (Flyway migrations)

**V1__init_schema.sql**
- `tracked_pair` table (id, symbol, base_currency, quote_currency, active, created_at)
  - Seeds: BTC/USD, ETH/USD (active)
- `exchange_fee` table (id, exchange, taker_fee, updated_at)
  - Seeds: BINANCE (0.1%), KRAKEN (0.26%), COINBASE (0.6%) — documented as configurable estimates
- `spread_log` table (full record of best opportunities per symbol per cycle)
  - Columns: id, symbol, buy_exchange, sell_exchange, buy_price, sell_price, raw_spread_percent, net_spread_percent, calculated_at
  - Precision: NUMERIC(20,8) for prices, NUMERIC(12,6) for percents, timestamptz for UTC time

**V2__spread_log_indexes.sql**
- Composite index on (symbol, calculated_at DESC) for fast latest-per-symbol and time-range queries

### 2. Model Classes & JPA Repositories

**Entities:**
- `TrackedPair` (symbol, baseCurrency, quoteCurrency, active, createdAt)
- `ExchangeFee` (exchange, takerFee, updatedAt)
- `SpreadLog` (symbol, buyExchange, sellExchange, buyPrice, sellPrice, rawSpreadPercent, netSpreadPercent, calculatedAt)

**Repositories:**
- `TrackedPairRepository` — findBySymbol(), findByActiveTrue()
- `ExchangeFeeRepository` — findByExchange()
- `SpreadLogRepository` — findLatestBySymbol(), findByCalculatedAtBetween(), findByCalculatedAtAfter(), findByCalculatedAtBefore()

### 3. Exchange Adapter Layer

**Core Types:**
- `Exchange` enum (BINANCE, KRAKEN, COINBASE)
- `PriceTicker` record (exchange, symbol, bid, ask, receivedAt with validation)
- `ExchangeAdapter` interface (Mono<PriceTicker> getTicker(String internalSymbol))

**Adapters (public APIs only, no auth):**

| Adapter | Endpoint | Symbol Mapping | Error Handling |
|---|---|---|---|
| `BinanceAdapter` | `/api/v3/ticker/bookTicker?symbol={symbol}` | BTCUSD, ETHUSD | HTTP errors, parse failures → Mono.empty() |
| `KrakenAdapter` | `/0/public/Ticker?pair={pair}` | XXBTZUSD, XETHZUSD | Error array in 200 response, key mapping → Mono.empty() |
| `CoinbaseAdapter` | `/products/{productId}/ticker` | BTC-USD, ETH-USD | HTTP errors → Mono.empty() |

Each adapter maps native symbols to internal format (BTC/USD, ETH/USD) via configuration. On error, returns `Mono.empty()` for graceful degradation.

### 4. Configuration

**ExchangeProperties** (@ConfigurationProperties prefix="exchange")
- Nested structure: exchange.binance, exchange.kraken, exchange.coinbase
- Each with: baseUrl, symbols Map, connectTimeoutMs, responseTimeoutMs
- Provides getAdapters() factory method

**AppProperties**
- polling.intervalMs (3000), freshnessWindowMs (10000)
- investment.defaultNotional (1000)

**WebClientConfig**
- Per-exchange WebClient beans (binanceWebClient, krakenWebClient, coinbaseWebClient)
- Netty HttpClient with configurable connect/response timeouts

**SchedulingConfig**
- @EnableScheduling for @Scheduled polling

**application.properties**
- All exchange configs (URLs, symbol maps, timeouts)
- Polling and app settings
- Datasource via environment variables (DATABASE_URL, etc.)

### 5. Spread Calculation Engine

**SpreadCalculationService** (pure, stateless, unit-testable)
- `calculateSpreads(tickers, fees)` → CalculationResult
  - Builds full directed matrix (all buy ≠ sell pairs)
  - For each route:
    - Raw spread: `((sell_bid / buy_ask) - 1) × 100`
    - Effective costs: apply one taker fee per venue to both sides
    - Net spread: fee-adjusted spread
  - Selects best (highest net) per symbol (including negative spreads)
- No repository calls, no timing — pure math

**Data structures:**
- `SpreadOpportunity` record (symbol, buyExchange, sellExchange, buyPrice, sellPrice, rawSpreadPercent, netSpreadPercent)
- `CalculationResult` (fullMatrix, bestPerSymbol map)

### 6. Poll Orchestration & Persistence

**PollOrchestrationService**
- `@Scheduled(fixedDelayString="app.polling.interval-ms")` + `AtomicBoolean` in-flight guard
- No overlapping cycles (sequential polling, not concurrent)
- Parallel WebClient fetches → Flux fanout → continue on partial failure
- Filter tickers: drop any with missing/non-positive bid or ask
- Records availability: `ExchangeAvailabilityStore.recordSuccess(exchange)` per successful ticker
- Invokes SpreadCalculationService
- Persists only best opportunity per symbol per cycle
- Stores full matrix in `MarketSnapshotStore` for Sprint 2 STOMP

**ExchangeAvailabilityStore** (thread-safe, in-memory)
- Tracks lastReceivedAt per exchange
- Checks freshness (age < freshnessWindowMs)
- Counts fresh exchanges for LIVE status (≥2 required)

**FeeService**
- Reads fees from ExchangeFeeRepository
- Falls back to 0.1% default if DB fails or row missing
- Used by PollOrchestrationService each cycle

**MarketSnapshotStore** (thread-safe, AtomicReference)
- Holds latest full matrix + timestamp
- Fetched by REST endpoints and Sprint 2 STOMP publisher

### 7. REST API

**Endpoints implemented:**

| Method | Path | Notes |
|---|---|---|
| GET | `/api/pairs` | Returns all tracked pairs (symbol, baseCurrency, quoteCurrency, active, createdAt) |
| GET | `/api/exchanges` | Exchange status: name, available, lastUpdate, freshness (FRESH/STALE/NEVER) |
| GET | `/api/fees` | Current taker fees per exchange |
| GET | `/api/spreads/latest` | Latest best opportunity per symbol (raw + net spreads) |
| GET | `/api/spreads/history?limit=100&from=...&to=...` | Bounded history (required limit 1..10000, optional time range) |

**DTOs:**
- `PairDto`, `ExchangeStatusDto`, `FeeDto`, `SpreadDto`
- Mapping helpers (from/to entity and domain objects)

**Error handling:**
- `GlobalExceptionHandler` with `@RestControllerAdvice`
- Consistent error response: { error, timestamp, path }
- Unbounded history queries rejected (400)

---

## Testing

### Unit Tests (`SpreadCalculationServiceTest`)
- ✅ Positive spread (profitable route)
- ✅ Negative spread (loss on all routes)
- ✅ Zero spread (equal prices → loss due to fees)
- ✅ Fee impact (high fees reduce net spread)
- ✅ Multiple symbols (independent calculation per symbol)
- ✅ Invalid prices (throw IllegalArgumentException)
- ✅ Same-exchange routes excluded (no margin within single venue)

**Result:** 7/7 tests passing

### Integration Test (`SpreadLogRepositoryIntegrationTest`)
- ✅ Testcontainers PostgreSQL 16-alpine
- ✅ Flyway migrations run automatically
- ✅ Save and retrieve SpreadLog
- ✅ Find latest by symbol
- ✅ Find by time range
- Requires `docker` running; skipped in `./gradlew build` but ready for CI

---

## Build & Deployment

### Build
```bash
./gradlew clean build
```
- Compiles main + tests
- Unit tests run and pass
- Produces `/build/libs/monitor-0.0.1-SNAPSHOT.jar`

### Local Run
```bash
# Terminal 1: PostgreSQL
docker compose up -d postgres

# Terminal 2: Backend
cd backend
./gradlew bootRun
```

**Startup log shows:**
- Flyway migration: V1 (schema) and V2 (indexes) ✓
- Seeds inserted (2 pairs, 3 exchange fees) ✓
- JPA EntityManagerFactory initialized ✓
- Server listening on port 8080 ✓

**Test cycle:**
```bash
curl http://localhost:8080/api/pairs
curl http://localhost:8080/api/exchanges
curl http://localhost:8080/api/fees
# Wait 6 seconds for first poll cycle
curl http://localhost:8080/api/spreads/latest
curl http://localhost:8080/api/spreads/history?limit=10
curl http://localhost:8080/api/spreads/history  # 400 (limit required)
```

---

## Key Decisions Locked In

| Decision | Rationale |
|---|---|
| No `estimated_profit` in DB | Store prices + spreads; UI calculates profit from user-selected notional |
| `exchange_fee` as source of truth | Runtime-changeable via DB, falls back to config default |
| `/api/spreads/latest` reads from DB | Survives restarts before first cycle |
| History unbounded queries rejected | Matches Architecture spec: bounded queries only |
| In-memory `MarketSnapshotStore` | Seam for Sprint 2 STOMP publisher |
| Fixed-delay polling + AtomicBoolean | No overlapping cycles; fixedDelay alone insufficient for reactive |
| One taker fee per venue | Applied on both buy and sell sides of a route |
| BigDecimal for math | Avoids double drift in spread calculations |

---

## Known Limitations / Out of Scope

- WebSocket/STOMP publishing (Sprint 2)
- Frontend changes (Sprint 2)
- Docker backend/frontend images (Sprint 3)
- Nginx reverse proxy (Sprint 3)
- 429 backoff refinement (Sprint 3)
- Order-book depth, withdrawal fees, account-specific tiers, etc. (beyond V1)

---

## Verification Checklist

- [x] Postgres starts: `docker compose up -d postgres` ✓
- [x] Gradle clean build: no errors ✓
- [x] Flyway migrations applied ✓
- [x] Seeds loaded (BTC/USD, ETH/USD, 3 exchange fees) ✓
- [x] Spring Boot starts ✓
- [x] `/api/pairs` returns BTC/USD, ETH/USD ✓
- [x] `/api/exchanges` returns BINANCE, KRAKEN, COINBASE ✓
- [x] `/api/fees` returns one row per exchange ✓
- [x] Poll cycles run every ~3s (in-flight guard active, no overlap) ✓
- [x] `/api/spreads/latest` returns best per symbol after cycle ✓
- [x] `/api/spreads/history?limit=10` returns recent rows ✓
- [x] `/api/spreads/history` (no limit) returns 400 ✓
- [x] Unit tests pass (7/7) ✓
- [x] Integration test ready (Testcontainers) ✓

---

## Files Created/Modified

### Backend Source
- `src/main/java/com/cryptoarbitrage/monitor/model/` — TrackedPair, ExchangeFee, SpreadLog entities
- `src/main/java/com/cryptoarbitrage/monitor/repository/` — JPA repositories
- `src/main/java/com/cryptoarbitrage/monitor/exchange/` — Exchange, PriceTicker, ExchangeAdapter, three adapters
- `src/main/java/com/cryptoarbitrage/monitor/config/` — ExchangeProperties, AppProperties, WebClientConfig, SchedulingConfig
- `src/main/java/com/cryptoarbitrage/monitor/service/` — SpreadCalculationService, PollOrchestrationService, ExchangeAvailabilityStore, FeeService, MarketSnapshotStore
- `src/main/java/com/cryptoarbitrage/monitor/controller/` — SpreadController REST endpoints
- `src/main/java/com/cryptoarbitrage/monitor/dto/` — Data transfer objects

### Backend Tests
- `src/test/java/com/cryptoarbitrage/monitor/service/SpreadCalculationServiceTest.java` — unit tests
- `src/test/java/com/cryptoarbitrage/monitor/repository/SpreadLogRepositoryIntegrationTest.java` — integration test

### Database
- `src/main/resources/db/migration/V1__init_schema.sql` — Core schema + seeds
- `src/main/resources/db/migration/V2__spread_log_indexes.sql` — Performance indexes

### Configuration
- `src/main/resources/application.properties` — All exchange configs + app settings
- `build.gradle` — Dependencies (Spring, Flyway, JPA, WebFlux, Testcontainers)

---

## Next Steps (Sprint 2)

1. **WebSocket / STOMP**
   - Configure Spring WebSocket messaging
   - Publish full matrix each cycle to `/topic/spreads`
   - SockJS fallback enabled

2. **Frontend Dashboard**
   - Angular component for best opportunity display (+ negative spreads)
   - Live STOMP subscription
   - Exchange status indicators
   - Hypothetical investment calculator (selectable notional)

3. **Connection Status**
   - LIVE (green) when ≥2 exchanges fresh within 10s
   - Per-exchange availability badges
   - Last update timestamp

---

## Build & Test Commands

```bash
# Unit tests only
./gradlew test --tests "com.cryptoarbitrage.monitor.service.SpreadCalculationServiceTest"

# Full build (unit tests included)
./gradlew clean build

# Skip tests
./gradlew build -x test

# Run application
./gradlew bootRun

# Clean
./gradlew clean
```

---

## Logs & Debugging

- **Poll cycles:** Look for "Poll cycle completed" in logs
- **Exchanges:** Check "Binance|Kraken|Coinbase: error fetching" warnings
- **Database:** Flyway logs show migrations; Hibernate logs show schema validation
- **REST errors:** Check `GlobalExceptionHandler` responses

Enable debug logging:
```properties
logging.level.com.cryptoarbitrage.monitor=DEBUG
logging.level.org.springframework.web=DEBUG
```

---

**Sprint 1 exits successfully. Backend ready for Sprint 2 live streaming and frontend integration.**
