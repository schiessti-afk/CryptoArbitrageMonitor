# Architecture

## Purpose

Crypto Arbitrage Monitor is a modular monolith that:

1. Polls public exchange APIs for top-of-book bid/ask prices
2. Normalizes responses into a shared ticker model
3. Computes indicative cross-venue spreads (raw and fee-adjusted)
4. Streams the full opportunity matrix to the Angular dashboard
5. Persists the best opportunity per symbol per cycle for history

No private exchange credentials and no trade execution. Displayed values are **indicative** — they do not model full slippage, withdrawal fees, transfer time, or execution risk.

## Repository layout

```
CryptoArbitrageMonitor/
├── backend/                 # Spring Boot 4 (com.cryptoarbitrage.monitor)
│   └── Dockerfile           # multi-stage bootJar → JRE
├── frontend/                # Angular 19 app
│   ├── Dockerfile           # multi-stage ng build → Nginx
│   └── nginx.conf           # SPA + /api + /ws proxy
├── docker-compose.yml       # postgres + backend + frontend
├── .env.example
├── LICENSE
├── README.md
└── docs/
    ├── ARCHITECTURE.md
    ├── RUN.md
    ├── IDEA.MD              # original product brief (historical)
    └── sprints/             # delivery plans and implementation notes
```

## Runtime topology

```
┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│ Binance  │ │  Kraken  │ │ Coinbase │ │  Bitget  │ │  KuCoin  │
│ public   │ │  public  │ │  public  │ │  public  │ │  public  │
└────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘
     └────────────┴────────────┼────────────┴────────────┘
                               v
                      Spring Boot backend
                 (adapters → engine → persist/publish)
                               │
                     ┌─────────┴─────────┐
                     v                   v
                PostgreSQL         STOMP / SockJS
                                         │
                                         v
                               Nginx → Angular SPA
```

`docker compose up --build` starts three services: **postgres**, **backend**, **frontend** (Nginx serving the production Angular build and proxying `/api` + SockJS `/ws` to the backend). Browser entry point: **http://localhost:8080** (override host port with `FRONTEND_PORT`). Backend listens on **8081** inside the Compose network and is not published by default. Host Postgres port **5437** stays mapped for hybrid local development.

## Backend package structure

Root package: `com.cryptoarbitrage.monitor`

| Package | Responsibility |
|---|---|
| `config` | Scheduling, WebClient, WebSocket/STOMP, CORS (`CorsConfig`), properties, market validation |
| `exchange` | `ExchangeAdapter` + Binance / Kraken / Coinbase / Bitget / KuCoin |
| `service` | Poll orchestration, spread calculation, preferences, backoff, status, publishing |
| `controller` | REST API + `GlobalExceptionHandler` |
| `repository` | Spring Data JPA |
| `model` | JPA entities |
| `dto` | REST and WebSocket payloads |

### Exchange adapter contract

```java
public interface ExchangeAdapter {
    Exchange getExchange();
    boolean supports(String internalSymbol);
    Mono<PriceTicker> getTicker(String internalSymbol);
    default Flux<PriceTicker> getTickers(Collection<String> internalSymbols) { /* fan-out */ }
    default Mono<OrderBook> getOrderBook(String internalSymbol, int depth) { /* optional */ }
}
```

Adapters own:

- HTTP calls to the exchange
- Native symbol mapping (`BTC/USD` → exchange product id)
- Response parsing into `PriceTicker` (bid, ask, optional sizes/volume, exchange, symbol, receivedAt)
- Optional on-demand order-book snapshots
- Exchange-specific error mapping

The rest of the system never depends on vendor JSON shapes. Callers check `supports(symbol)` before polling so unlisted markets are not treated as failures.

### Symbol mapping

Internal symbols use `BASE/QUOTE` (e.g. `BTC/USD`, `ETH/USDT`). Quote universes are never mixed in one comparison.

| Internal (examples) | Typical native mapping (illustrative) |
|---|---|
| `BTC/USD` | Binance `BTCUSD`, Kraken `XBT/USD`, Coinbase `BTC-USD` |
| `BTC/USDT` | Binance `BTCUSDT`, Kraken `XBT/USDT`, Coinbase `BTC-USDT`, Bitget/KuCoin batched all-tickers |
| `BNB/USDT` | Binance / Bitget / KuCoin only (no Kraken or Coinbase listing in V1) |

Exact native ids live in `application.properties` / `ExchangeProperties`. Do not treat USDT pairs as USD. Coverage: **50 tracked pairs** (5 USD + 45 USDT); see README for venue tables.

## Polling and concurrency

- Target interval: **every 3 seconds**
- Markets polled: client-enabled list via `PUT /api/preferences/poll` (enable order preserved); bootstrap default is all USD + major five USDT
- Fetches for a cycle run **in parallel** via WebClient/`Mono`
- **No overlapping cycles**: a cycle must finish before the next scheduled run starts
- **Batch venues** (Binance, Kraken, Bitget, KuCoin): one HTTP call per venue per cycle
- **Coinbase**: per-product calls, capped at **8 products per cycle** (core symbols first, then client enable order)
- If one exchange times out or errors, the cycle **continues with available exchanges**
- Frontend status: **LIVE (green)** when ≥ **2** visible exchanges have ticker `receivedAt` within the freshness window (default **10 seconds**)

## Liquidity and order-book depth

- **Poll cycle:** ticker adapters parse optional `bidSize`, `askSize`, and `quoteVolume24h` from existing batch/single ticker responses where venues expose them. These flow into the STOMP snapshot as `buyAskSize`, `sellBidSize`, and optional 24h volumes per leg — no extra poll traffic.
- **On-demand depth:** `GET /api/orderbook/route?symbol=&buyExchange=&sellExchange=&depth=20` fetches order-book snapshots from the buy and sell adapters in parallel. Depth is **never** attached to the 3s poll loop.
- **Persistence:** liquidity and depth are live-only; `spread_log` history rows do not store size or book data.

## Spread engine

For each polled symbol, build the **full directed matrix** of exchange pairs where buy exchange ≠ sell exchange:

- Buy price = ask on buy venue
- Sell price = bid on sell venue

```
Raw Spread % = ((Sell Price / Buy Price) - 1) × 100

Effective Buy Cost     = Buy Price × (1 + BuyExchangeTakerFee)
Effective Sell Revenue = Sell Price × (1 - SellExchangeTakerFee)

Net Spread % = (Effective Sell Revenue / Effective Buy Cost - 1) × 100
```

Fees: **one taker fee per exchange**, applied on both buy and sell sides of a route using that venue’s fee.

Estimated profit uses a **user-selectable** notional (default **$1,000**). Prefer storing prices and spreads server-side and deriving display profit from the selected notional in the UI.

### What is published vs persisted

| Channel | Content |
|---|---|
| STOMP `/topic/spreads` | Full matrix, best-per-symbol, exchange health, coverage for polled symbols |
| Dashboard primary view | Best opportunity per symbol (highest net spread), including **negative** values |
| `spread_log` table | Only the best opportunity per symbol per cycle |

## Real-time channel

```
Backend → Spring WebSocket → STOMP → SockJS → Angular
Topic: /topic/spreads
```

SockJS remains enabled for transport fallback. REST is used for bootstrap, preferences, history, and on-demand depth; the live matrix is push-based (no frontend polling loop for spreads).

## REST API

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/pairs` | Active tracked pairs |
| `GET` | `/api/exchanges` | Exchange metadata + health/freshness |
| `GET` | `/api/fees` | Current taker fees |
| `GET` | `/api/config` | Frontend defaults (notional, freshness, fees, quote assets) |
| `PUT` | `/api/preferences/poll` | Ordered enabled-market list — controls backend polling |
| `GET` | `/api/spreads/latest` | Latest best row per symbol |
| `GET` | `/api/spreads/history` | Required `limit`; optional `from` / `to` |
| `GET` | `/api/orderbook/route` | On-demand depth for a buy/sell route |
| `GET` | `/api/database/stats` | DB / `spread_log` size and row counts |
| `DELETE` | `/api/database/spread-log` | Clear persisted spread history |

History is always **bounded**. Reject or clamp unbounded queries. API errors use a stable `{error, timestamp, path}` JSON body.

## Data model

Flyway migrations through **V6**. Do not store raw vendor payloads.

### `tracked_pair`

- `id`, `symbol`, `base_currency`, `quote_currency`, `active`, `created_at`
- Seed evolves across migrations: V1 USD majors → V4 SOL/XRP/DOGE/BNB → V5/V6 extended USDT (**50** active symbols)

### `exchange_fee`

- `id`, `exchange`, `taker_fee`, `updated_at`
- One fee row per exchange (buy and sell use the same taker fee)

### `spread_log`

- `id`, `symbol`, `buy_exchange`, `sell_exchange`
- `buy_price`, `sell_price`
- `raw_spread_percent`, `net_spread_percent`
- `calculated_at`

## Frontend structure

```
frontend/src/app/
├── components/
│   ├── dashboard/
│   ├── spread-table/            # full matrix accordion
│   ├── spread-detail/           # top opportunities
│   ├── connection-status/       # LIVE badge
│   ├── settings-drawer/
│   ├── usdt-market-picker/
│   └── order-book-drawer/
├── directives/
│   └── flash-on-change.directive.ts
├── services/
│   ├── spread.service.ts
│   ├── websocket.service.ts
│   ├── settings.service.ts
│   ├── poll-preference.service.ts
│   ├── quote-asset.service.ts
│   ├── order-book.service.ts
│   └── database.service.ts
├── utils/                       # filters, liquidity, formatting
└── models/
```

UI copy uses **indicative cross-venue arbitrage opportunity**. Investment notional is selectable; default `$1,000`.

**Flash-on-change:** the standalone `appFlashOnChange` directive watches a bound value and briefly applies `.flash-change` when it changes. Gated by `prefers-reduced-motion: no-preference`.

Production image: `ng build` artifacts served by **Nginx**. Relative `/api` and `/ws` URLs work unchanged because Nginx reverse-proxies them to the backend. Local hybrid development uses the Angular dev server on **:4200** with the same relative paths proxied to **:8081**.

## Error handling

| Scenario | Behavior |
|---|---|
| Single exchange down | Mark unavailable; compute matrix on remaining venues |
| Fewer than 2 fresh exchanges | Status not green; still show last known data where useful |
| Invalid / missing bid-ask | Exclude that ticker from the cycle matrix |
| DB write failure | Log error; prefer not to block publishing live updates |
| Rate limit / HTTP 429 or 418 | Back off that venue; skip it until the window ends |
| Request / response timeout | Same backoff path as rate limits |
| Unhandled API exception | `GlobalExceptionHandler` → 500 with generic message; stack in logs only |

### Exchange backoff (429 / timeout)

| Piece | Role |
|---|---|
| `ExchangeBackoffFilter` | Attached to every exchange `WebClient`; records backoff on HTTP **429** / **418** and on timeout-like failures |
| `ExchangeBackoffStore` | Per-venue exponential skip window; thread-safe |
| `PollOrchestrationService` | Skips adapters still in backoff; other venues continue |

**Policy:**

- Initial window: `app.polling.backoff-initial-ms` (default **15s**)
- On repeated failures while still hot: doubles up to `app.polling.backoff-max-ms` (default **120s**)
- After the window expires, a clean success resets the multiplier
- An active window is **not** cancelled by a parallel success in the same cycle (important for Coinbase’s per-product fan-out)
- Adapters still degrade to empty tickers for the failing call; the matrix continues on remaining venues

## Exchange API limits

Documented for operators; V1 uses low-frequency polling with batched venue requests and remains best-effort. Client-driven selective polling keeps typical cycles far smaller than the theoretical maximum (~9 HTTP calls per 3s cycle when many markets are enabled).

| Exchange | Public REST guidance (summary) | V1 posture |
|---|---|---|
| **Binance** | Weight-based IP limits; `429` / `418` on abuse | **1 batched** `/api/v3/ticker/bookTicker` per cycle |
| **Kraken** | Counter-based limits; public calls increment a decaying counter | **1 batched** `/0/public/Ticker?pair=…` per cycle |
| **Coinbase** | Public REST often ~**10 req/s/IP** | **≤8 product ticker calls/cycle** — core first, then enable order |
| **Bitget** | Public REST limits vary; all-tickers is one call | **1** `/api/v2/spot/market/tickers` per cycle |
| **KuCoin** | Public REST limits vary; all-tickers is one call | **1** `/api/v1/market/allTickers` per cycle |

**Total:** ~4 batch-venue requests / 3s ≈ **1.3 req/s** plus Coinbase per-product calls (≤8 per cycle).

Always verify current vendor docs when changing poll interval or symbol count.

## Configuration

- Datasource via env: `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`
  - Local hybrid: `jdbc:postgresql://localhost:5437/arbitrage`
  - Compose backend: `jdbc:postgresql://postgres:5432/arbitrage`
- Compose frontend host port: `FRONTEND_PORT` (default **8080**)
- Exchange base URLs, timeouts, default fees, poll interval, freshness window (10s), and investment default via Spring configuration
- Backoff: `app.polling.backoff-initial-ms`, `app.polling.backoff-max-ms`
- Actuator: only `/actuator/health` exposed (Compose healthcheck; blocked at Nginx edge)
- API errors: `GlobalExceptionHandler` returns `{error,timestamp,path}` — 404 / 400 for bad input / 500 with generic message
- No private API keys required for V1

## Testing focus

| Area | Intent |
|---|---|
| `SpreadCalculationService` | Raw/net math, fees, equal prices, negatives, invalid inputs |
| Adapters | Fixture JSON → `PriceTicker` + symbol mapping (including batch fixtures) |
| `ExchangeBackoffStore` / `ExchangeBackoffFilter` | Exponential windows, 429/418/timeout recording, parallel-success race |
| Persistence | Spring Boot + PostgreSQL repository/Flyway path |

## Non-goals (architecture)

Microservices, Redis, Kafka, Kubernetes, private trading APIs, user accounts, cloud deployment, and trade execution are out of scope for V1.
