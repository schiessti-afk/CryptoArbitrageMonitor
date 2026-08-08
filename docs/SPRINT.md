# Sprint plan

Five sprints to a runnable V1 (Sprint 0 = repo/toolchain). Scope is locked to the decisions in the product brief and [ARCHITECTURE.md](./ARCHITECTURE.md).

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
- [x] Postgres service in `docker-compose.yml` (backend/frontend images in Sprint 4)
- [x] MIT `LICENSE`, root `.gitignore`, `.env.example`
- [x] README local-dev prerequisites and run commands

### Sprint 0 exit criteria

A developer can install the toolchain below, start Postgres via Compose, run the backend Gradle build, and serve the Angular scaffold.

### What you need installed

| Tool | Version | Why |
|---|---|---|
| **JDK** | **17+** (Temurin recommended) | Spring Boot backend |
| **Node.js** | **20+** (LTS) | Angular CLI / npm |
| **Docker Desktop** | recent | PostgreSQL (and full stack in Sprint 4) |
| **Git** | any recent | version control |

Optional: IntelliJ IDEA / VS Code / Cursor. No system Maven required — use `backend/gradlew`. No global `ng` required — use `npx ng` or `npm start` in `frontend/`.

---

## Sprint 1 — Core backend

**Goal:** Poll exchanges, compute spreads, persist best opportunities, expose REST bootstrap APIs.

### Deliverables

- [x] Spring Boot project under `backend/` (`com.cryptoarbitrage.monitor`) — scaffolded in Sprint 0
- [x] Flyway migrations: `tracked_pair`, `exchange_fee`, `spread_log`
- [x] Seed pairs: `BTC/USD`, `ETH/USD` (USD markets)
- [x] Seed one taker fee per exchange
- [x] `ExchangeAdapter` + Binance / Kraken / Coinbase adapters (public APIs only)
- [x] Native symbol mapping to internal `BTC/USD` / `ETH/USD`
- [x] Scheduled poll every ~3s with **in-flight guard** (no overlapping cycles)
- [x] Parallel WebClient fetches; continue on partial exchange failure
- [x] Full matrix calculation (raw + net with single taker fee per exchange)
- [x] Persist **best opportunity per symbol per cycle** only
- [x] REST: `/api/pairs`, `/api/exchanges`, `/api/fees`, `/api/spreads/latest`
- [x] REST: `/api/spreads/history` with required `limit` + optional `from`/`to`
- [x] Unit tests for spread calculation
- [ ] Adapter tests with response fixtures — fixtures for all three; only `KrakenAdapterTest` so far
- [x] At least one DB integration test (app + PostgreSQL + repository)

### Sprint 1 exit criteria

Backend can run against Postgres, complete poll cycles with ≥1 exchange, write best rows, and serve REST without the frontend.

---

## Sprint 2 — Realtime + dashboard

**Goal:** Push the full matrix live and render a clear monitoring UI.

### Deliverables

- [x] Spring WebSocket + STOMP + SockJS
- [x] Publish full matrix each cycle to `/topic/spreads`
- [x] Angular app under `frontend/`
- [x] WebSocket service (STOMP/SockJS) + REST services
- [x] Dashboard: best opportunity per symbol (including negative net spreads)
- [x] Spread matrix / table view fed by the live topic
- [x] Connection status: **green/LIVE** when ≥2 exchanges have fresh data within **10 seconds**
- [x] Per-exchange availability indicators
- [x] User-selectable hypothetical investment (default **$1,000**); estimated profit updates in UI
- [ ] Copy uses **indicative cross-venue arbitrage opportunity** — in README; not yet on the dashboard
- [x] Last update timestamp and clear bid/ask buy-sell direction

### Sprint 2 exit criteria

With backend from Sprint 1, the dashboard updates live without frontend polling and correctly reflects partial exchange outages and negative best spreads.

---

## Sprint 3 — Asset expansion, settings, and dashboard UX

**Goal:** Grow the tracked universe from 2 assets to 6, put the dashboard under user control, and
raise the UI to a state-of-the-art monitoring surface.

Full breakdown in [SPRINT3-PLAN.md](./SPRINT3-PLAN.md).

### Phase 1 — Asset universe expansion

- [ ] Live-probe native symbols for SOL, XRP, DOGE, BNB on all five venues before writing config
- [ ] Settle whether `api.binance.com` actually serves USD spot markets (affects the existing BTC/ETH USD config too)
- [ ] Batch the ticker fetch per venue (`getTickers`) — Binance, Kraken, Bitget, KuCoin; Coinbase has no batch endpoint
- [ ] Migration `V4`: `SOL/USD`, `XRP/USD`, `DOGE/USD`, `SOL/USDT`, `XRP/USDT`, `DOGE/USDT`, `BNB/USDT`
- [ ] Config entries in `application.properties` for every probed market (BNB is USDT-only — no Coinbase or Kraken listing)
- [ ] Per-symbol coverage in the published snapshot so thin markets explain themselves
- [ ] `MarketConfigValidator`: warn on under-covered symbols and config/DB drift
- [ ] Batch-response fixtures and tests, including a partial batch
- [ ] Post-batching request rates recorded in [ARCHITECTURE.md](./ARCHITECTURE.md)

### Phase 2 — Settings

- [ ] `SettingsService` — signal-backed, `localStorage`-persisted, merge-over-defaults
- [ ] Gear icon opening a settings drawer: venues, markets, opportunity threshold, appearance, advanced
- [ ] Disable any exchange or pair; default state shows everything
- [ ] Min net-spread threshold with dim-vs-hide choice
- [ ] Dark mode and density stored (rendered in Phase 3)
- [ ] Freshness-window and default-notional overrides on top of `/api/config`
- [ ] One consolidated filter pipeline; best-per-symbol recomputed when a venue is hidden
- [ ] LIVE badge recomputed over visible venues so it agrees with what is on screen
- [ ] Visible active-filter chips and a "showing N of M" count — nothing hidden without a marker

### Phase 3 — Dashboard UX

- [ ] Sticky header (badge, age, quote toggle, notional, gear) and a KPI tile row
- [ ] Opportunities ranked globally by net spread, not one unordered card per symbol
- [ ] Mirrored A→B / B→A routes collapsed by default, with a show-both toggle
- [ ] Quick-filter chips: All (default) / Positive net / Above threshold
- [ ] Magnitude-aware price precision, tabular figures, quote-aware currency labels, flash-on-change
- [ ] Dark mode and density rendered across all components via a semantic token layer
- [ ] Distinct loading / filtered-empty / no-data states, using Phase 1 coverage data
- [ ] Accessibility pass: focus management in the drawer, `aria-live` on the badge only, responsive matrix
- [ ] `OnPush` + stable track keys + `computed()` filter chain ahead of ~5× the row count
- [ ] Indicative-comparison disclaimer on the dashboard (closes the open Sprint 2 item above)

### Sprint 3 exit criteria

SOL, XRP, DOGE and BNB stream live alongside BTC and ETH without raising per-venue request rate above
the Sprint 1 budget; a settings panel can hide any venue or market with cards, matrix, status chips
and LIVE badge all reflecting it consistently; the default view still shows everything.

---

## Sprint 4 — Ship V1

**Goal:** Production-like local deploy, hardening, docs, and DoD checklist.

> Renumbered from Sprint 3 on 2026-08-08 when the sprint above was inserted. The Sprint 1 and
> Sprint 2 documents still refer to this work as "Sprint 3" and were left as written.

### Deliverables

- [ ] `docker-compose.yml`: postgres, backend, frontend — Postgres only so far
- [ ] Frontend Dockerfile: production `ng build` + **Nginx**
- [ ] Nginx routing for SPA + API/WebSocket proxy as required
- [x] Env-based DB config; externalized exchange URLs/timeouts/fees
- [x] Documented exchange API limit summary (see Architecture)
- [ ] Backoff / graceful handling for timeouts and `429`s
- [ ] README quick start verified from clean checkout
- [x] MIT `LICENSE`
- [ ] Final pass on logging and API error responses
- [ ] Fill remaining gaps from the V1 Definition of Done below

### Sprint 4 exit criteria

A new developer can clone, run `docker compose up --build`, and see live indicative opportunities for `BTC/USD` and `ETH/USD`.

---

## V1 Definition of Done

- [x] Spring Boot application starts successfully
- [x] PostgreSQL starts through Docker Compose
- [x] Flyway creates the required schema
- [x] Binance, Kraken, and Coinbase public integrations work
- [x] Responses normalized through `ExchangeAdapter`
- [x] `BTC/USD` and `ETH/USD` monitored as USD markets
- [x] Prices refresh about every 3 seconds without overlapping cycles
- [x] Full buy/sell matrix calculated each cycle
- [x] Raw and net spreads calculated with one taker fee per exchange
- [x] Estimated profit supports selectable notional (default $1,000)
- [x] Only best opportunity per symbol persisted each cycle
- [x] Full matrix published on STOMP `/topic/spreads` (SockJS enabled)
- [x] Angular dashboard shows best opportunity (including negative) and live updates
- [x] Status green when ≥2 exchanges are fresh within 10 seconds
- [x] Exchange failures degrade gracefully; monitoring continues
- [x] History API is bounded (`limit` + optional time range)
- [x] Unit tests cover the calculation engine
- [x] Integration tests cover database functionality
- [ ] Entire stack starts with `docker compose up --build`
- [x] MIT license present

---

## Explicitly out of sprint scope

Trade execution, private API keys, user accounts, USDT-as-USD shortcuts, Redis/Kafka/microservices, cloud deployment, advanced charting, order-book depth, withdrawal/network fees, and notifications.