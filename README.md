# Crypto Arbitrage Monitor

Monitor live cryptocurrency bid/ask prices across **Binance**, **Kraken**, **Coinbase**, **Bitget**, and **KuCoin**, compute indicative cross-venue spreads after taker fees, and stream results to a real-time dashboard.

This application does **not** execute trades. Displayed values are **indicative cross-venue arbitrage opportunities** based on public top-of-book prices and configurable fee estimates. They do not model order-book depth, slippage, withdrawal fees, transfer time, or execution risk.

## Features

- Public market data from **five exchanges** (no private API keys)
- **31 tracked symbols** in the database — **5 USD** and **26 USDT** markets (BNB is USDT-only on three venues)
- **Selective polling** — the backend fetches only markets you enable in the dashboard; adding or removing a coin updates the next poll cycle via `PUT /api/preferences/poll`
- Batched ticker fetches for Binance, Kraken, Bitget, and KuCoin (one HTTP call per venue per cycle); Coinbase uses per-product calls for selected markets only
- Normalized bid/ask tickers via exchange adapters with live-probed native symbol mapping
- Full buy/sell matrix per polling cycle, with the **best opportunity per symbol** highlighted and persisted
- Raw and net spread (one configurable taker fee per exchange)
- **Default view: USDT** — major five (BTC, ETH, SOL, XRP, DOGE) shown by default; chip picker to add or remove extended USDT markets without cluttering the UI
- **USD markets** — all five pairs always available; unchanged checkbox list in settings
- **Settings drawer** — hide venues/markets, min net-spread threshold (dim or hide), theme, density, optional freshness/notional overrides (persisted in `localStorage`)
- Quote-asset toggle (**USDT** / **USD**) with consistent filtering across opportunities, matrix, status chips, and LIVE badge
- Ranked **Top Opportunities** panel with quick filters (All / Positive net / Above threshold), KPI tiles, thin-market coverage messages
- **Full Matrix** — accordion per market (collapsed by default); expand/collapse individual symbols or use Expand all / Collapse all
- User-selectable hypothetical investment size (default **$1,000**)
- Live updates over **WebSocket + STOMP + SockJS** (frontend does not poll)
- **Flash-on-change** — matrix and opportunity cells briefly pulse when prices or spreads update
- Historical spread log with bounded REST queries
- Graceful degradation when an exchange fails
- **429 / timeout backoff** — rate-limited or timed-out venues are skipped for an exponential window (15s–120s) while others keep polling
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

Flyway applies migrations through **V5** on startup (adds 20 USDT expansion assets).

## Monitored markets

The database tracks **31 pairs** across two **quote universes** — never mixed in a single comparison. The dashboard **polls only the markets you select**; the tables below describe what is configured, not what is fetched every cycle.

### USD (5 symbols — all shown by default)

| Symbol | Venues |
|---|---|
| `BTC/USD`, `ETH/USD`, `SOL/USD` | Binance, Kraken, Coinbase |
| `XRP/USD`, `DOGE/USD` | Kraken, Coinbase |

Binance global spot lists SOL/USD but not XRP/USD or DOGE/USD (verified live).

### USDT (26 symbols — major five shown by default)

**Default enabled:** `BTC/USDT`, `ETH/USDT`, `SOL/USDT`, `XRP/USDT`, `DOGE/USDT`

**Extended (add via chip picker):** BNB, ADA, AVAX, LINK, SUI, DOT, TON, LTC, BCH, SHIB, PEPE, UNI, NEAR, APT, ATOM, FIL, ARB, OP, INJ, AAVE, WIF

| Symbol group | Venues |
|---|---|
| `BTC/USDT` … `DOGE/USDT` | Binance, Kraken, Coinbase, Bitget, KuCoin |
| `BNB/USDT` | Binance, Bitget, KuCoin |
| `ADA/USDT`, `AVAX/USDT`, `LINK/USDT`, `DOT/USDT`, `SHIB/USDT`, `ATOM/USDT` | Binance, Kraken, Bitget, KuCoin; Coinbase when selected |
| `NEAR/USDT`, `OP/USDT` | Binance, Bitget, KuCoin; Coinbase when selected |
| `SUI/USDT`, `PEPE/USDT`, `UNI/USDT`, `APT/USDT`, `FIL/USDT`, `ARB/USDT`, `INJ/USDT`, `AAVE/USDT`, `WIF/USDT` | Binance, Bitget, KuCoin |
| `TON/USDT` | Binance, Kraken, KuCoin |
| `LTC/USDT`, `BCH/USDT` | Binance, Kraken, Bitget, KuCoin |

Each adapter maps exchange-native product ids (e.g. Kraken `XDGUSD` for DOGE/USD, KuCoin `BTC-USDT`) into internal symbols. Venue coverage details and probe notes: [docs/SPRINT-USDT-EXPANSION.md](docs/SPRINT-USDT-EXPANSION.md).

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

1. Resolve **selected markets** from client preferences (`PUT /api/preferences/poll`), or before the first sync use the bootstrap default (all USD + major five USDT)
2. Fetch tickers via **batched** adapter calls for Binance, Kraken, Bitget, and KuCoin, plus per-product Coinbase calls for selected symbols that venue lists
3. Build the full directed buy/sell matrix for each polled symbol
4. Apply one taker fee per exchange to produce net spreads
5. Publish the matrix, best-per-symbol, exchange health, and **symbol coverage** for polled markets on `/topic/spreads`
6. Persist only the **best opportunity per symbol** for that cycle

The frontend syncs enabled markets whenever you toggle chips in the USDT bar or settings drawer. Disabled markets are not fetched from exchanges.

**Live status** is green when at least **two** exchanges have fresh ticker data for the selected quote universe within the freshness window (default **10 seconds**). When venues are hidden in settings, the badge recomputes over **visible** venues only.

## Dashboard UX

| Area | Behavior |
|---|---|
| Header | USDT/USD toggle (USDT default), notional input, settings |
| USDT bar | Major-five chips always visible; tap **+ N more** to add extended markets |
| Top Opportunities | Ranked cards with All / Positive / Above threshold filters; cells flash on value change |
| Full Matrix | One collapsed row per market; tap to expand routes; Expand all / Collapse all; price/spread cells flash on change |
| Settings | Venues, USD checkboxes, USDT chip picker with Major 5 only / All USDT presets |

## API (overview)

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/pairs` | All tracked pairs in the database |
| `GET` | `/api/exchanges` | Exchange status and freshness |
| `GET` | `/api/fees` | Configured taker fees |
| `GET` | `/api/config` | Frontend defaults (notional, freshness window, fees, quote assets) |
| `PUT` | `/api/preferences/poll` | Enabled market list — controls which symbols the backend polls |
| `GET` | `/api/spreads/latest` | Latest best opportunities |
| `GET` | `/api/spreads/history` | Bounded history (`limit`, optional time range) |

Live matrix updates: STOMP topic `/topic/spreads` (SockJS-enabled endpoint). Snapshots include `coverage[]` with configured vs fresh venue counts per polled symbol.

## Documentation

- [Local run](docs/RUN.md) — start Postgres, backend, and frontend
- [Architecture](docs/ARCHITECTURE.md) — components, data flow, persistence, API limits
- [Sprint plan](docs/SPRINT.md) — delivery sprints and definition of done
- [Sprint 3 implementation](docs/SPRINT3-IMPLEMENTATION.md) — asset expansion, settings, dashboard UX
- [USDT expansion](docs/SPRINT-USDT-EXPANSION.md) — 20-market rollout, venue probe table, poll preferences
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

Polling is **best effort** with graceful degradation. Batch venues (Binance, Kraken, Bitget, KuCoin) issue **one request per cycle** regardless of how many symbols you select; adapters filter to your enabled markets. Coinbase issues **one request per selected product** it lists.

If a venue returns **HTTP 429/418** or a request times out, the backend **backs off that venue** (default 15s, doubling to 120s on repeats) and skips it on subsequent poll cycles until the window ends. Other exchanges continue. Details: [Architecture — exchange backoff](docs/ARCHITECTURE.md#error-handling).

With the default five USDT markets plus five USD pairs, load stays well within public rate limits. See [Architecture](docs/ARCHITECTURE.md#exchange-api-limits) for vendor guidance.

## License

[MIT](LICENSE)
