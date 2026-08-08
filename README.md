# Crypto Arbitrage Monitor

Monitor live cryptocurrency bid/ask prices across **Binance**, **Kraken**, **Coinbase**, **Bitget**, and **KuCoin**, compute indicative cross-venue spreads after taker fees, and stream results to a real-time dashboard.

This application does **not** execute trades. Displayed values are **indicative cross-venue arbitrage opportunities** based on public top-of-book prices and configurable fee estimates. The dashboard shows **top-of-book liquidity chips** and **on-demand order-book depth** when you select a route; these remain indicative and do not model full slippage, withdrawal fees, transfer time, or execution risk.

## Features

- Public market data from **five exchanges** (no private API keys)
- **50 tracked symbols** — **5 USD** and **45 USDT** markets in Postgres (BNB is USDT-only on three venues; MKR is not tracked — Binance-only)
- **Selective polling** — backend fetches only markets you enable; toggling a chip updates the next cycle via `PUT /api/preferences/poll` (enable order preserved for Coinbase prioritization)
- Batched ticker fetches for Binance, Kraken, Bitget, and KuCoin (one HTTP call per venue per cycle); Coinbase uses per-product calls capped at **8 products per cycle**
- Normalized bid/ask tickers via exchange adapters with live-probed native symbol mapping
- **Liquidity at top-of-book** — Thin / OK / Deep chips vs your notional, plus 24h quote volume when the venue exposes it in ticker JSON (no extra poll traffic)
- **Order-book depth drawer** — click any route to load ~20 ask/bid levels on buy and sell venues (`GET /api/orderbook/route`; on demand, not part of the 3s poll)
- Full buy/sell matrix per cycle, with the **best opportunity per symbol** highlighted and persisted
- Raw and net spread (one configurable taker fee per exchange)
- **Default view: USDT** — major five (BTC, ETH, SOL, XRP, DOGE) enabled by default; chip picker to add/remove extended USDT markets
- **USD markets** — all five pairs always available; checkbox list in settings
- **Settings drawer** — venues, markets, opportunity threshold, theme, density, freshness/notional overrides, **database stats / clear history** (persisted in `localStorage` except DB actions)
- Quote-asset toggle (**USDT** / **USD**) with consistent filtering across opportunities, matrix, status chips, and LIVE badge
- Ranked **Top Opportunities** with quick filters (All / Positive net / Above threshold), KPI tiles, thin-market coverage messages
- **Full Matrix** — accordion per market (collapsed by default); Expand all / Collapse all; liquidity chips on routes; click a row for depth
- **Flash-on-change** — matrix and opportunity cells briefly pulse when prices or spreads update (`prefers-reduced-motion` respected)
- User-selectable hypothetical investment size (default **$1,000**)
- Live updates over **WebSocket + STOMP + SockJS** (frontend does not poll spread data)
- Historical spread log with bounded REST queries
- **429 / timeout backoff** — rate-limited or timed-out venues are skipped for an exponential window (15s–120s) while others keep polling
- Graceful degradation when an exchange fails
- Dark mode (system / light / dark) and comfortable/compact density

## Stack

| Layer | Technology |
|---|---|
| Backend | Java 17+, Spring Boot 4, WebClient, WebSocket/STOMP/SockJS, Spring Data JPA, Flyway |
| Frontend | Angular 19, TypeScript, Signals, STOMP/SockJS client, Tailwind CSS |
| Data | PostgreSQL |
| Runtime | Docker Compose (PostgreSQL + Spring Boot + Nginx-served Angular) |

Java package root: `com.cryptoarbitrage.monitor`

## Architecture

High-level data path from public venues to dashboard and history:

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           Exchange APIs                                  │
│         Binance · Kraken · Coinbase · Bitget · KuCoin (public)           │
└───────────────────────────────┬──────────────────────────────────────────┘
                                │
                                v
┌──────────────────────────────────────────────────────────────────────────┐
│                        WebSocket Ingestion                               │
│              Live market-data intake (adapters / poll cycle)             │
└───────────────────────────────┬──────────────────────────────────────────┘
                                │
                                v
┌──────────────────────────────────────────────────────────────────────────┐
│                       Normalization Engine                               │
│         Vendor JSON → shared bid/ask ticker (symbol, size, volume)       │
└───────────────────────────────┬──────────────────────────────────────────┘
                                │
                                v
┌──────────────────────────────────────────────────────────────────────────┐
│                       Arbitrage Evaluator                                │
│      Cross-venue matrix · raw & fee-adjusted spreads · best per symbol   │
└───────────────────────────────┬──────────────────────────────────────────┘
                                │
                                v
┌──────────────────────────────────────────────────────────────────────────┐
│                     Alert / Logging Output                               │
│     STOMP `/topic/spreads` (dashboard) · Postgres `spread_log` (history) │
└──────────────────────────────────────────────────────────────────────────┘
```

Deeper package layout, adapters, backoff, and API limits: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Prerequisites (local development)

| Tool | Version | Notes |
|---|---|---|
| JDK | 17+ | Temurin 17 recommended. Set `JAVA_HOME` if an older JRE is still on `PATH`. |
| Node.js | 20+ LTS | Ships with npm |
| Docker Desktop | recent | Full stack via Compose (or Postgres-only for hybrid local-dev) |
| Git | recent | Clone / commits |

Use the Gradle wrapper and npm scripts — no global Maven or Angular CLI required.

### Verify

```bash
docker version         # required for Compose quick start
java -version          # 17+ if running backend locally
node -v                # 20+ if running frontend locally
npm -v
```

Windows example if `java -version` shows 1.8:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot'
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
```

## Quick start (Docker Compose)

```bash
docker compose up --build
```

Open **http://localhost:8080**

Compose starts PostgreSQL, the Spring Boot backend, and Nginx serving the production Angular build (proxying `/api` and SockJS `/ws`). Flyway applies migrations through **V6** on backend startup.

If port 8080 is already in use:

```bash
# bash / macOS / Linux
FRONTEND_PORT=8888 docker compose up --build

# PowerShell
$env:FRONTEND_PORT='8888'; docker compose up --build
```

Default DB credentials: database `arbitrage`, user/password `arbitrage`. Host Postgres port **5437** remains mapped for hybrid local development (see `.env.example`). Full run notes: [docs/RUN.md](docs/RUN.md).

## Local development (hybrid)

```bash
# 1) Start PostgreSQL
docker compose up -d postgres

# 2) Backend (from backend/)
cd backend
.\gradlew.bat bootRun          # Windows
# ./gradlew bootRun            # macOS / Linux

# 3) Frontend (from frontend/, second terminal)
cd frontend
npm install
npm start                      # http://localhost:4200
```

Details and troubleshooting: [docs/RUN.md](docs/RUN.md).

## Monitored markets

The database tracks **50 pairs** across two **quote universes** — never mixed in one comparison. The dashboard **polls only markets you select**; tables below describe configured coverage, not every-cycle fetch volume.

### USD (5 symbols — all enabled by default)

| Symbol | Venues |
|---|---|
| `BTC/USD`, `ETH/USD`, `SOL/USD` | Binance, Kraken, Coinbase |
| `XRP/USD`, `DOGE/USD` | Kraken, Coinbase |

Binance global spot lists SOL/USD but not XRP/USD or DOGE/USD (verified live).

### USDT (45 symbols — major five enabled by default)

**Default enabled:** `BTC/USDT`, `ETH/USDT`, `SOL/USDT`, `XRP/USDT`, `DOGE/USDT`

**Extended (chip picker):** BNB, ADA, AVAX, LINK, SUI, DOT, TON, LTC, BCH, SHIB, PEPE, UNI, NEAR, APT, ATOM, FIL, ARB, OP, INJ, AAVE, WIF, TRX, POL, ETC, ALGO, VET, ICP, HBAR, SEI, TIA, STX, RUNE, JUP, WLD, FET, RENDER, TAO, ENA, ONDO, PENDLE

| Symbol group | Venues |
|---|---|
| `BTC/USDT` … `DOGE/USDT` | Binance, Kraken, Coinbase, Bitget, KuCoin |
| `BNB/USDT` | Binance, Bitget, KuCoin |
| `ADA/USDT`, `AVAX/USDT`, `LINK/USDT`, `DOT/USDT`, `SHIB/USDT`, `ATOM/USDT` | Binance, Kraken, Bitget, KuCoin; Coinbase when within per-cycle budget |
| `NEAR/USDT`, `OP/USDT`, `HBAR/USDT`, `STX/USDT`, `FET/USDT` | Binance, Bitget, KuCoin; Coinbase when within per-cycle budget |
| `ALGO/USDT`, `VET/USDT` | Binance, Kraken, Bitget, KuCoin |
| `LTC/USDT`, `BCH/USDT` | Binance, Kraken, Bitget, KuCoin |
| `TON/USDT` | Binance, Kraken, KuCoin |
| Remaining extended USDT (see list above) | Binance, Bitget, KuCoin |

`POL/USDT` replaces MATIC on Bitget/KuCoin naming. Native symbol mapping and probe notes: [docs/sprints/SPRINT-USDT-EXPANSION.md](docs/sprints/SPRINT-USDT-EXPANSION.md) and [docs/sprints/SPRINT.md](docs/sprints/SPRINT.md).

## How it works

Every ~3 seconds the backend runs a **non-overlapping** poll cycle:

1. Resolve **selected markets** from client preferences (`PUT /api/preferences/poll`), preserving enable order, or before first sync use bootstrap default (all USD + major five USDT)
2. Skip venues in **backoff** after HTTP 429/418 or timeout-like failures
3. Fetch tickers: **one batched call** each for Binance, Kraken, Bitget, KuCoin; **up to 8** Coinbase product calls (core symbols first, then remaining enabled in client order)
4. Parse optional top-of-book size and 24h volume into the live snapshot when venues expose them
5. Build the full directed buy/sell matrix for each polled symbol; apply one taker fee per exchange
6. Publish matrix, best-per-symbol, exchange health, and **coverage** for polled symbols on `/topic/spreads`
7. Persist only the **best opportunity per symbol** for that cycle

The frontend syncs enabled markets when you toggle USDT chips or settings checkboxes. Disabled markets are not fetched.

**Live status** is green when ≥2 exchanges have fresh data for the selected quote universe within the freshness window (default **10 s**). Hidden venues are excluded from the visible LIVE recompute.

**Depth** is separate: clicking a route calls `/api/orderbook/route` once; it does not run on the poll timer.

## Dashboard UX

| Area | Behavior |
|---|---|
| Header | USDT/USD toggle (USDT default), notional, settings |
| USDT bar | Major-five chips; **+ N more** for extended markets |
| Top Opportunities | Ranked cards; All / Positive / Above threshold; liquidity chips; click route → depth drawer |
| Full Matrix | Collapsed accordion per market; Expand/Collapse all; liquidity chips; click route → depth drawer |
| Depth drawer | Side panel with buy-venue asks and sell-venue bids for the selected route |
| Settings | Venues; USD checkboxes; USDT chip picker (Major 5 / All USDT); appearance; advanced overrides; **Data** (DB size, row count, clear history) |

## API (overview)

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/pairs` | All tracked pairs |
| `GET` | `/api/exchanges` | Exchange status and freshness |
| `GET` | `/api/fees` | Configured taker fees |
| `GET` | `/api/config` | Frontend defaults (notional, freshness window, fees, quote assets) |
| `PUT` | `/api/preferences/poll` | Ordered enabled-market list — controls backend polling |
| `GET` | `/api/spreads/latest` | Latest best opportunities |
| `GET` | `/api/spreads/history` | Bounded history (`limit`, optional time range) |
| `GET` | `/api/orderbook/route` | On-demand depth for a route (`symbol`, `buyExchange`, `sellExchange`, `depth`) |
| `GET` | `/api/database/stats` | Database and `spread_log` size / row counts |
| `DELETE` | `/api/database/spread-log` | Clear persisted spread history |

Live updates: STOMP topic `/topic/spreads` (SockJS). Snapshots include `coverage[]`, liquidity fields on opportunities when available, and per-quote LIVE flags.

## Documentation

| Doc | Contents |
|---|---|
| [Local run](docs/RUN.md) | Compose full stack and hybrid local development |
| [Architecture](docs/ARCHITECTURE.md) | Data flow, adapters, liquidity/depth, backoff, API limits |
| [Sprint plan](docs/sprints/SPRINT.md) | Delivery sprints and V1 definition of done |
| [Sprint 4](docs/sprints/SPRINT4-PLAN.md) · [notes](docs/sprints/SPRINT4-IMPLEMENTATION.md) | Docker Compose ship + hardening |
| [Sprint 3 notes](docs/sprints/SPRINT3-IMPLEMENTATION.md) | Asset expansion, settings, dashboard UX |
| [USDT expansion](docs/sprints/SPRINT-USDT-EXPANSION.md) | V5/V6 markets and selective polling |
| [Product brief](docs/IDEA.MD) | Original idea notes (historical; see Architecture for shipped V1) |

## Limitations

V1 does **not** fully model:

- Slippage beyond visible depth levels
- Withdrawal, deposit, or network fees
- Transfer delays or withdrawal restrictions
- Account-specific fee tiers
- Minimum order sizes or execution failure

The dashboard footer states this explicitly. Use **indicative cross-venue arbitrage opportunity**, not guaranteed profit.

## Exchange API usage

Polling is **best effort** with graceful degradation.

- **Batch venues** (Binance, Kraken, Bitget, KuCoin): one request per cycle; adapters filter to your enabled symbols
- **Coinbase**: one request per product polled, max **8 per cycle** (core-first, then client enable order)
- **Backoff**: venues returning 429/418 or timing out are skipped until an exponential window elapses (15s → 120s)

With the default five USDT + five USD markets, Coinbase may still cap at 8 products when all core symbols are enabled. See [Architecture — exchange API limits](docs/ARCHITECTURE.md#exchange-api-limits) and [error handling](docs/ARCHITECTURE.md#error-handling).

## License

[MIT](LICENSE)
