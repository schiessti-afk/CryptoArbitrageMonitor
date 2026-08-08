# Architecture

## Purpose

Crypto Arbitrage Monitor is a modular monolith that:

1. Polls public exchange APIs for top-of-book bid/ask prices
2. Normalizes responses into a shared ticker model
3. Computes indicative cross-venue spreads (raw and fee-adjusted)
4. Streams the full opportunity matrix to the Angular dashboard
5. Persists the best opportunity per symbol per cycle for history

No private exchange credentials and no trade execution.

## Repository layout

```
CryptoArbitrageMonitor/
├── backend/                 # Spring Boot 4 (com.cryptoarbitrage.monitor)
├── frontend/                # Angular 19 app
├── docker-compose.yml       # Postgres in Sprint 0; full stack in Sprint 4
├── .env.example
├── LICENSE
├── README.md
└── docs/
```

## Runtime topology

```
┌─────────────┐   ┌─────────────┐   ┌─────────────┐
│   Binance   │   │   Kraken    │   │  Coinbase   │
│  public API │   │  public API │   │  public API │
└──────┬──────┘   └──────┬──────┘   └──────┬──────┘
       │                 │                 │
       └────────────┬────┴────────────┬────┘
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

`docker compose up` starts three services: **postgres**, **backend**, **frontend** (Nginx serving the production Angular build and proxying API/WebSocket as needed).

## Backend package structure

Root package: `com.cryptoarbitrage.monitor`

| Package | Responsibility |
|---|---|
| `config` | Scheduling, WebClient, WebSocket/STOMP, CORS, properties |
| `exchange` | `ExchangeAdapter` + Binance / Kraken / Coinbase implementations |
| `service` | Poll orchestration, spread calculation, status, publishing |
| `controller` | REST API |
| `repository` | Spring Data JPA |
| `model` | JPA entities |
| `dto` | REST and WebSocket payloads |

### Exchange adapter contract

```java
public interface ExchangeAdapter {
    Exchange getExchange();
    Mono<PriceTicker> getTicker(String internalSymbol);
}
```

Adapters own:

- HTTP calls to the exchange
- Native symbol mapping (`BTC/USD` → exchange product id)
- Response parsing into `PriceTicker` (bid, ask, exchange, symbol, receivedAt)
- Exchange-specific error mapping

The rest of the system never depends on vendor JSON shapes.

### Symbol mapping (V1)

Internal symbols are USD markets:

| Internal | Typical native mapping (illustrative) |
|---|---|
| `BTC/USD` | Binance `BTCUSD`, Kraken `XBTUSD` / `XBT/USD`, Coinbase `BTC-USD` |
| `ETH/USD` | Binance `ETHUSD`, Kraken `ETHUSD` / `ETH/USD`, Coinbase `ETH-USD` |

Exact native ids live in adapter configuration. Do not treat USDT pairs as USD.

## Polling and concurrency

- Target interval: **every 3 seconds**
- Fetches for a cycle run **in parallel** via WebClient/`Mono`
- **No overlapping cycles**: a cycle must finish (success, partial success, or failure) before the next scheduled run starts (scheduler lock / in-flight guard)
- If one exchange times out or errors, the cycle **continues with available exchanges**
- Frontend status: **LIVE (green)** when ≥ **2** exchanges have ticker `receivedAt` within the last **10 seconds**

## Spread engine

For each symbol, build the **full directed matrix** of exchange pairs where buy exchange ≠ sell exchange:

- Buy price = ask on buy venue
- Sell price = bid on sell venue

```
Raw Spread % = ((Sell Price / Buy Price) - 1) × 100

Effective Buy Cost     = Buy Price × (1 + BuyExchangeTakerFee)
Effective Sell Revenue = Sell Price × (1 - SellExchangeTakerFee)

Net Spread % = (Effective Sell Revenue / Effective Buy Cost - 1) × 100
```

Fees: **one taker fee per exchange**, applied on both buy and sell sides of a route using that venue’s fee.

Estimated profit uses a **user-selectable** notional (default **$1,000**). Calculation is client-adjustable for display; persisted history may store profit at the server default or omit notional-specific profit — prefer storing prices and spreads, and deriving display profit from the selected notional in the UI.

### What is published vs persisted

| Channel | Content |
|---|---|
| STOMP `/topic/spreads` | **Full matrix** every successful/partial cycle |
| Dashboard primary view | **Best opportunity per symbol** (highest net spread), including **negative** values |
| `spread_log` table | **Only the best opportunity per symbol per cycle** |

## Real-time channel

```
Backend → Spring WebSocket → STOMP → SockJS → Angular
Topic: /topic/spreads
```

SockJS remains enabled for transport fallback and as part of the Spring messaging integration.

REST is used for bootstrap and history; the live matrix is push-based (no frontend polling loop).

## REST API

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/pairs` | Active tracked pairs |
| `GET` | `/api/exchanges` | Exchange metadata + health/freshness |
| `GET` | `/api/fees` | Current taker fees |
| `GET` | `/api/spreads/latest` | Latest best row per symbol |
| `GET` | `/api/spreads/history` | Required `limit`; optional `from` / `to` time range |

History is always **bounded**. Reject or clamp unbounded queries.

## Data model (V1)

### `tracked_pair`

- `id`, `symbol`, `base_currency`, `quote_currency`, `active`, `created_at`
- Seed: `BTC/USD`, `ETH/USD`

### `exchange_fee`

- `id`, `exchange`, `taker_fee`, `updated_at`
- One fee row per exchange (buy and sell use the same taker fee)

### `spread_log`

- `id`, `symbol`, `buy_exchange`, `sell_exchange`
- `buy_price`, `sell_price`
- `raw_spread_percent`, `net_spread_percent`
- `calculated_at`
- Optional: participating exchange set / cycle id if useful for debugging

Do not store raw vendor payloads. Flyway owns all schema changes.

## Frontend structure

```
frontend/src/app/
├── components/
│   ├── dashboard/
│   ├── spread-table/          # full matrix
│   ├── spread-detail/         # best opportunity focus
│   └── connection-status/     # LIVE when ≥2 fresh exchanges / 10s
├── services/
│   ├── spread.service.ts
│   ├── websocket.service.ts
│   └── exchange.service.ts
└── models/
```

UI copy uses **indicative cross-venue arbitrage opportunity**. Investment notional is selectable; default `$1,000`.

Production image: `ng build` artifacts served by **Nginx** (not the Angular dev server).

## Error handling

| Scenario | Behavior |
|---|---|
| Single exchange down | Mark unavailable; compute matrix on remaining venues |
| Fewer than 2 fresh exchanges | Status not green; still show last known data where useful |
| Invalid / missing bid-ask | Exclude that ticker from the cycle matrix |
| DB write failure | Log error; prefer not to block publishing live updates if possible |
| Rate limit / HTTP 429 | Back off that adapter; degrade gracefully |

## Exchange API limits

Documented for operators; V1 uses low-frequency polling with batched venue requests (Sprint 3: 11 symbols, ~9 HTTP calls per 3s cycle) and remains best-effort.

| Exchange | Public REST guidance (summary) | V1 posture (post-Sprint 3 batching) |
|---|---|---|
| **Binance** | Weight-based IP limits (commonly on the order of thousands of weight/minute; light ticker calls are low weight). `429` / `418` on abuse. | **1 batched** `/api/v3/ticker/bookTicker` per cycle (~0.33 req/s) |
| **Kraken** | Counter-based limits; public calls increment a decaying counter (starter tier is relatively tight). | **1 batched** `/0/public/Ticker?pair=…` per cycle (~0.33 req/s) |
| **Coinbase** | Public REST often limited around **10 requests/sec/IP** (burst slightly higher depending on product). | **5** `/products/{id}/ticker` calls per cycle for USD symbols (~1.67 req/s) — no batch best-bid/ask |
| **Bitget** | Public REST limits vary by endpoint; all-tickers is one call. | **1** `/api/v2/spot/market/tickers` per cycle (~0.33 req/s) |
| **KuCoin** | Public REST limits vary; all-tickers is one call. | **1** `/api/v1/market/allTickers` per cycle (~0.33 req/s) |

**Total:** ~9 requests / 3s ≈ **3 req/s** across five venues (down from ~12 req/s with per-symbol polling at 4 symbols).

Always verify current vendor docs when changing poll interval or symbol count. Do not assume limits are static.

## Configuration

- Datasource via env: `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`
- Exchange base URLs, timeouts, default fees, poll interval, freshness window (10s), and investment default via Spring configuration
- No private API keys required for V1

## Testing focus

| Area | Intent |
|---|---|
| `SpreadCalculationService` | Raw/net math, fees, equal prices, negatives, invalid inputs |
| Adapters | Fixture JSON → `PriceTicker` + symbol mapping |
| Persistence | Spring Boot + PostgreSQL repository/Flyway path |

## Non-goals (architecture)

Microservices, Redis, Kafka, Kubernetes, private trading APIs, user accounts, and trade execution are out of scope for V1.