# Sprint plan

Four sprints to a runnable V1 (Sprint 0 = repo/toolchain). Scope is locked to the decisions in the product brief and [ARCHITECTURE.md](./ARCHITECTURE.md).

**Package:** `com.cryptoarbitrage.monitor`  
**License:** MIT  
**Delivery:** `docker compose up --build` runs Postgres + backend + Nginx-served Angular

---

## Sprint 0 — Repo and toolchain

**Goal:** Scaffold the monorepo and document the local development stack so Sprint 1 can start immediately.

### Deliverables

- [x] Repository layout: `backend/`, `frontend/`, `docs/`, `docker-compose.yml`
- [x] Spring Boot project under `backend/` (`com.cryptoarbitrage.monitor`) with Gradle wrapper
- [x] Package folders: `config`, `exchange`, `service`, `controller`, `repository`, `model`, `dto`
- [x] Angular app under `frontend/` with Tailwind, STOMP/SockJS client deps, SPA (no SSR)
- [x] Frontend folders: `components/`, `services/`, `models/`
- [x] Postgres service in `docker-compose.yml` (backend/frontend images in Sprint 3)
- [x] MIT `LICENSE`, root `.gitignore`, `.env.example`
- [x] README local-dev prerequisites and run commands

### Sprint 0 exit criteria

A developer can install the toolchain below, start Postgres via Compose, run the backend Gradle build, and serve the Angular scaffold.

### What you need installed

| Tool | Version | Why |
|---|---|---|
| **JDK** | **17+** (Temurin recommended) | Spring Boot backend |
| **Node.js** | **20+** (LTS) | Angular CLI / npm |
| **Docker Desktop** | recent | PostgreSQL (and full stack in Sprint 3) |
| **Git** | any recent | version control |

Optional: IntelliJ IDEA / VS Code / Cursor. No system Maven required — use `backend/gradlew`. No global `ng` required — use `npx ng` or `npm start` in `frontend/`.

---

## Sprint 1 — Core backend

**Goal:** Poll exchanges, compute spreads, persist best opportunities, expose REST bootstrap APIs.

### Deliverables

- [ ] Spring Boot project under `backend/` (`com.cryptoarbitrage.monitor`) — scaffolded in Sprint 0
- [ ] Flyway migrations: `tracked_pair`, `exchange_fee`, `spread_log`
- [ ] Seed pairs: `BTC/USD`, `ETH/USD` (USD markets)
- [ ] Seed one taker fee per exchange
- [ ] `ExchangeAdapter` + Binance / Kraken / Coinbase adapters (public APIs only)
- [ ] Native symbol mapping to internal `BTC/USD` / `ETH/USD`
- [ ] Scheduled poll every ~3s with **in-flight guard** (no overlapping cycles)
- [ ] Parallel WebClient fetches; continue on partial exchange failure
- [ ] Full matrix calculation (raw + net with single taker fee per exchange)
- [ ] Persist **best opportunity per symbol per cycle** only
- [ ] REST: `/api/pairs`, `/api/exchanges`, `/api/fees`, `/api/spreads/latest`
- [ ] REST: `/api/spreads/history` with required `limit` + optional `from`/`to`
- [ ] Unit tests for spread calculation
- [ ] Adapter tests with response fixtures
- [ ] At least one DB integration test (app + PostgreSQL + repository)

### Sprint 1 exit criteria

Backend can run against Postgres, complete poll cycles with ≥1 exchange, write best rows, and serve REST without the frontend.

---

## Sprint 2 — Realtime + dashboard

**Goal:** Push the full matrix live and render a clear monitoring UI.

### Deliverables

- [ ] Spring WebSocket + STOMP + SockJS
- [ ] Publish full matrix each cycle to `/topic/spreads`
- [ ] Angular app under `frontend/`
- [ ] WebSocket service (STOMP/SockJS) + REST services
- [ ] Dashboard: best opportunity per symbol (including negative net spreads)
- [ ] Spread matrix / table view fed by the live topic
- [ ] Connection status: **green/LIVE** when ≥2 exchanges have fresh data within **10 seconds**
- [ ] Per-exchange availability indicators
- [ ] User-selectable hypothetical investment (default **$1,000**); estimated profit updates in UI
- [ ] Copy uses **indicative cross-venue arbitrage opportunity**
- [ ] Last update timestamp and clear bid/ask buy-sell direction

### Sprint 2 exit criteria

With backend from Sprint 1, the dashboard updates live without frontend polling and correctly reflects partial exchange outages and negative best spreads.

---

## Sprint 3 — Ship V1

**Goal:** Production-like local deploy, hardening, docs, and DoD checklist.

### Deliverables

- [ ] `docker-compose.yml`: postgres, backend, frontend
- [ ] Frontend Dockerfile: production `ng build` + **Nginx**
- [ ] Nginx routing for SPA + API/WebSocket proxy as required
- [ ] Env-based DB config; externalized exchange URLs/timeouts/fees
- [ ] Documented exchange API limit summary (see Architecture)
- [ ] Backoff / graceful handling for timeouts and `429`s
- [ ] README quick start verified from clean checkout
- [ ] MIT `LICENSE`
- [ ] Final pass on logging and API error responses
- [ ] Fill remaining gaps from the V1 Definition of Done below

### Sprint 3 exit criteria

A new developer can clone, run `docker compose up --build`, and see live indicative opportunities for `BTC/USD` and `ETH/USD`.

---

## V1 Definition of Done

- [ ] Spring Boot application starts successfully
- [ ] PostgreSQL starts through Docker Compose
- [ ] Flyway creates the required schema
- [ ] Binance, Kraken, and Coinbase public integrations work
- [ ] Responses normalized through `ExchangeAdapter`
- [ ] `BTC/USD` and `ETH/USD` monitored as USD markets
- [ ] Prices refresh about every 3 seconds without overlapping cycles
- [ ] Full buy/sell matrix calculated each cycle
- [ ] Raw and net spreads calculated with one taker fee per exchange
- [ ] Estimated profit supports selectable notional (default $1,000)
- [ ] Only best opportunity per symbol persisted each cycle
- [ ] Full matrix published on STOMP `/topic/spreads` (SockJS enabled)
- [ ] Angular dashboard shows best opportunity (including negative) and live updates
- [ ] Status green when ≥2 exchanges are fresh within 10 seconds
- [ ] Exchange failures degrade gracefully; monitoring continues
- [ ] History API is bounded (`limit` + optional time range)
- [ ] Unit tests cover the calculation engine
- [ ] Integration tests cover database functionality
- [ ] Entire stack starts with `docker compose up --build`
- [ ] MIT license present

---

## Explicitly out of sprint scope

Trade execution, private API keys, user accounts, USDT-as-USD shortcuts, Redis/Kafka/microservices, cloud deployment, advanced charting, order-book depth, withdrawal/network fees, and notifications.