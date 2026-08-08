# Crypto Arbitrage Monitor

Monitor live cryptocurrency bid/ask prices across Binance, Kraken, and Coinbase, compute indicative cross-venue spreads after taker fees, and stream results to a real-time dashboard.

This application does **not** execute trades. Displayed values are **indicative cross-venue arbitrage opportunities** based on public top-of-book prices and configurable fee estimates. They do not model order-book depth, slippage, withdrawal fees, transfer time, or execution risk.

## Features

- Public market data from **Binance**, **Kraken**, and **Coinbase** (no private API keys)
- Normalized bid/ask tickers via exchange adapters
- Full buy/sell matrix per polling cycle, with the **best opportunity per symbol** highlighted and persisted
- Raw and net spread (one configurable taker fee per exchange)
- User-selectable hypothetical investment size (default **$1,000**)
- Live updates over **WebSocket + STOMP + SockJS**
- Historical spread log with bounded REST queries
- Graceful degradation when an exchange fails
- One-command local run via **Docker Compose**

## Stack

| Layer | Technology |
|---|---|
| Backend | Java 17+, Spring Boot 4, WebClient, WebSocket/STOMP/SockJS, Spring Data JPA, Flyway |
| Frontend | Angular 19, TypeScript, RxJS, Signals, STOMP/SockJS client, Tailwind CSS |
| Data | PostgreSQL |
| Runtime | Docker Compose (PostgreSQL now; backend + Nginx-served Angular in Sprint 3) |

Java package root: `com.cryptoarbitrage.monitor`

## Prerequisites (local development)

| Tool | Version | Notes |
|---|---|---|
| JDK | 17+ | Temurin 17 recommended. Set `JAVA_HOME` if an older JRE is still on `PATH`. |
| Node.js | 20+ LTS | Ships with npm; used for the Angular app |
| Docker Desktop | recent | Runs PostgreSQL (and the full stack later) |
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

## Local development (Sprint 0+)

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

## Monitored markets (V1)

| Internal symbol | Quote |
|---|---|
| `BTC/USD` | USD |
| `ETH/USD` | USD |

These are **USD markets**, not USDT stand-ins. Each adapter maps the exchange-native product id (for example Coinbase `BTC-USD`, Kraken `XBT/USD`) into the internal symbol.

## Quick start

**Today (Sprint 0):** Postgres via Compose; run backend and frontend locally (see above).

**After Sprint 3:**

```bash
docker compose up --build
```

Typical local URLs:

- Angular dev server: `http://localhost:4200`
- Backend API: `http://localhost:8080`
- Production-style dashboard (Sprint 3+): `http://localhost`

### Environment

Configure via environment variables (see Compose file):

- `DATABASE_URL`
- `DATABASE_USERNAME`
- `DATABASE_PASSWORD`

Exchange base URLs and default fees are Spring configuration properties and can be overridden without code changes.

## How it works

Every ~3 seconds the backend runs a **non-overlapping** poll cycle:

1. Fetch tickers asynchronously from available exchanges
2. Build the full directed buy/sell matrix for each symbol
3. Apply one taker fee per exchange to produce net spreads
4. Publish the **full matrix** on `/topic/spreads`
5. Persist only the **best opportunity per symbol** for that cycle

**Live status** is green when at least **two** exchanges have fresh ticker data within the last **10 seconds**.

## API (overview)

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/pairs` | Tracked pairs |
| `GET` | `/api/exchanges` | Exchange status |
| `GET` | `/api/fees` | Configured taker fees |
| `GET` | `/api/spreads/latest` | Latest best opportunities |
| `GET` | `/api/spreads/history` | Bounded history (`limit`, optional time range) |

Live matrix updates: STOMP topic `/topic/spreads` (SockJS-enabled endpoint).

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — components, data flow, persistence, limits
- [Sprint plan](docs/SPRINT.md) — three delivery sprints and definition of done
- [Product idea / notes](docs/IDEA.MD) — original design notes

## Limitations

V1 does **not** account for:

- Order-book depth or slippage
- Withdrawal, deposit, or network fees
- Transfer delays or withdrawal restrictions
- Account-specific fee tiers
- Minimum order sizes or execution failure

Use the UI language **indicative cross-venue arbitrage opportunity**, not guaranteed profit.

## Exchange API usage

Polling is **best effort** with graceful degradation. Public rate limits are documented in [Architecture](docs/ARCHITECTURE.md#exchange-api-limits). The app continues with whichever exchanges respond successfully in a given cycle.

## License

[MIT](LICENSE)