# Crypto Arbitrage Monitor

Monitor live cryptocurrency bid/ask prices across **Binance**, **Kraken**, **Coinbase**, **Bitget**, and **KuCoin**, compute indicative cross-venue spreads after taker fees, and stream results to a real-time dashboard.

This application does **not** execute trades. Displayed values are **indicative cross-venue arbitrage opportunities** based on public top-of-book prices and configurable fee estimates. They do not model order-book depth, slippage, withdrawal fees, transfer time, or execution risk.

## Features

- Public market data from **five exchanges** (no private API keys)
- **11 tracked symbols** — BTC, ETH, SOL, XRP, DOGE, and BNB across **USD** and **USDT** quote universes (BNB is USDT-only)
- Batched ticker fetches (~9 HTTP calls per 3s cycle) to keep request rate low as symbol count grows
- Normalized bid/ask tickers via exchange adapters with live-probed native symbol mapping
- Full buy/sell matrix per polling cycle, with the **best opportunity per symbol** highlighted and persisted
- Raw and net spread (one configurable taker fee per exchange)
- **Settings drawer** — hide venues/markets, min net-spread threshold (dim or hide), theme, density, optional freshness/notional overrides (all persisted in `localStorage`)
- Quote-asset toggle (**USD** / **USDT**) with consistent filtering across cards, matrix, chips, and LIVE badge
- Ranked **Top Opportunities** panel, KPI tiles, full-width sortable matrix, thin-market coverage messages
- User-selectable hypothetical investment size (default **$1,000**)
- Live updates over **WebSocket + STOMP + SockJS** (frontend does not poll)
- Historical spread log with bounded REST queries
- Graceful degradation when an exchange fails
- Dark mode (system / light / dark) and comfortable/compact density

## Stack

| Layer | Technology |
|---|---|
| Backend | Java 17+, Spring Boot 4, WebClient, WebSocket/STOMP/SockJS, Spring Data JPA, Flyway |
| Frontend | Angular 19, TypeScript, Signals, STOMP/SockJS client, Tailwind CSS |
| Data | PostgreSQL |
| Runtime | Docker Compose (PostgreSQL now; backend + Nginx-served Angular in Sprint 4) |

Java package root: `com.cryptoarbitrage.monitor`

## Prerequisites (local development)

| Tool | Version | Notes |
|---|---|---|
| JDK | 17+ | Temurin 17 recommended. Set `JAVA_HOME` if an older JRE is still on `PATH`. |
| Node.js | 20+ LTS | Ships with npm; used for the Angular app |
| Docker Desktop | recent | Runs PostgreSQL (and the full stack in Sprint 4) |
| Git | recent | Clone / commits |

You do **not** need a global Maven or Angular CLI install. Use the Gradle wrapper and npm scripts.

### Verify

```bash
java -version          # must show 17+ (not 1.8)
node -v                # 20+
npm -v
docker version
```

If `java -version` still shows 1.8, point `JAVA_HOME` at JDK 17 (example Temurin path):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot'
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
```

## Local development

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

Default DB credentials match Compose / `.env.example`: database `arbitrage`, user/password `arbitrage`, host port `5437` (mapped to container `5432`).

Flyway applies migrations through **V4** on startup (adds SOL, XRP, DOGE, BNB).

## Monitored markets

Two **quote universes** — never mixed in a single comparison:

### USD (7 symbols)

| Symbol | Venues |
|---|---|
| `BTC/USD`, `ETH/USD`, `SOL/USD` | Binance, Kraken, Coinbase |
| `XRP/USD`, `DOGE/USD` | Kraken, Coinbase |

Binance global spot lists SOL/USD but not XRP/USD or DOGE/USD (verified live).

### USDT (4 symbols × 5 venues, except BNB)

| Symbol | Venues |
|---|---|
| `BTC/USDT`, `ETH/USDT`, `SOL/USDT`, `XRP/USDT`, `DOGE/USDT` | Binance, Kraken, Coinbase, Bitget, KuCoin |
| `BNB/USDT` | Binance, Bitget, KuCoin |

Each adapter maps exchange-native product ids (e.g. Kraken `XDGUSD` for DOGE/USD, KuCoin `BTC-USDT`) into internal symbols.

## Quick start

**Today:** Postgres via Compose; run backend and frontend locally (see [docs/RUN.md](docs/RUN.md)).

**After Sprint 4:** `docker compose up --build` for the full stack.

Typical local URLs:

- Angular dev server: `http://localhost:4200`
- Backend API: `http://localhost:8081`
- Production-style dashboard (Sprint 4+): `http://localhost`

### Environment

Configure via environment variables (see Compose file):

- `DATABASE_URL`
- `DATABASE_USERNAME`
- `DATABASE_PASSWORD`

Exchange base URLs and default fees are Spring configuration properties and can be overridden without code changes.

## How it works

Every ~3 seconds the backend runs a **non-overlapping** poll cycle:

1. Fetch tickers via **batched** adapter calls (one request per venue where supported)
2. Build the full directed buy/sell matrix for each symbol
3. Apply one taker fee per exchange to produce net spreads
4. Publish the **full matrix**, best-per-symbol, exchange health, and **symbol coverage** on `/topic/spreads`
5. Persist only the **best opportunity per symbol** for that cycle

**Live status** is green when at least **two** exchanges have fresh ticker data for the selected quote universe within the freshness window (default **10 seconds**). When venues are hidden in settings, the badge recomputes over **visible** venues only.

## API (overview)

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/pairs` | Tracked pairs |
| `GET` | `/api/exchanges` | Exchange status |
| `GET` | `/api/fees` | Configured taker fees |
| `GET` | `/api/config` | Frontend defaults (notional, freshness window, fees) |
| `GET` | `/api/spreads/latest` | Latest best opportunities |
| `GET` | `/api/spreads/history` | Bounded history (`limit`, optional time range) |

Live matrix updates: STOMP topic `/topic/spreads` (SockJS-enabled endpoint). Snapshots include `coverage[]` with configured vs fresh venue counts per symbol.

## Documentation

- [Local run](docs/RUN.md) — start Postgres, backend, and frontend
- [Architecture](docs/ARCHITECTURE.md) — components, data flow, persistence, API limits
- [Sprint plan](docs/SPRINT.md) — delivery sprints and definition of done
- [Sprint 3 implementation](docs/SPRINT3-IMPLEMENTATION.md) — asset expansion, settings, dashboard UX
- [Product idea / notes](docs/IDEA.MD) — original design notes

## Limitations

V1 does **not** account for:

- Order-book depth or slippage
- Withdrawal, deposit, or network fees
- Transfer delays or withdrawal restrictions
- Account-specific fee tiers
- Minimum order sizes or execution failure

The dashboard footer states this explicitly. Use the language **indicative cross-venue arbitrage opportunity**, not guaranteed profit.

## Exchange API usage

Polling is **best effort** with graceful degradation. After Sprint 3 batching, the app issues ~**9 requests per 3s cycle** (~3 req/s total). Public rate limits are documented in [Architecture](docs/ARCHITECTURE.md#exchange-api-limits). The app continues with whichever exchanges respond successfully in a given cycle.

## License

[MIT](LICENSE)
