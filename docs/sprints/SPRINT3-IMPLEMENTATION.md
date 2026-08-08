# Sprint 3 Implementation — Asset expansion, settings, and dashboard UX

**Status:** Implemented and verified (2026-08-08). Backend unit tests and frontend build/tests pass on JDK 17.

Plan reference: [SPRINT3-PLAN.md](./SPRINT3-PLAN.md)

---

## Overview

Sprint 3 grows the tracked universe from 4 to **11 symbols** (6 base assets × two quote universes, with BNB USDT-only), batches venue ticker fetches to stay **below** the Sprint 1 request budget, adds a client-side settings drawer, and restructures the dashboard into a ranked monitoring surface with theme/density support.

---

## Phase 1 — Asset universe expansion

### 1.1 Live probes (gate)

Probed before writing config. Key findings recorded in `application.properties` comments:

| Finding | Action |
|---|---|
| `api.binance.com` serves **BTC/USD, ETH/USD, SOL/USD** | Kept Binance USD entries for those three |
| Binance **XRPUSD / DOGEUSD** return `-1121 Invalid symbol` | No Binance config for XRP/USD or DOGE/USD — those USD pairs are **Kraken + Coinbase only** (2 venues) |
| Kraken Dogecoin response keys are **`XDGUSD` / `XDGUSDT`**, not `DOGE*` | Native symbols set accordingly |
| Kraken USDT pairs use plain keys (`SOLUSDT`, `XRPUSDT`, …) | Config matches live response keys |
| BNB/USDT on Binance, Bitget, KuCoin | Added; **no BNB/USD row** (Kraken/Coinbase do not list a comparable spot market) |
| Coinbase BNB-USDT returns NotFound | No Coinbase BNB entry |

### 1.2 Batch ticker fetch

**Interface:** `ExchangeAdapter.getTickers(Collection<String>)` with default fan-out to `getTicker` (Coinbase path).

| Venue | Batch endpoint | Calls/cycle |
|---|---|---|
| Binance | `/api/v3/ticker/bookTicker?symbols=[…]` | 1 |
| Kraken | `/0/public/Ticker?pair=A,B,C` | 1 |
| Bitget | `/api/v2/spot/market/tickers` (all, filtered client-side) | 1 |
| KuCoin | `/api/v1/market/allTickers` (filtered client-side) | 1 |
| Coinbase | `/products/{id}/ticker` per product | 5 (USD symbols only when USD quote selected) |

**Total:** ~9 HTTP requests per 3s cycle (~3 req/s) — below the ~12 req/cycle figure at 4 symbols pre-batching.

**Files:**
- `ExchangeAdapter.java` — default + contract
- `BinanceAdapter.java`, `KrakenAdapter.java`, `BitgetAdapter.java`, `KuCoinAdapter.java` — batch overrides
- `PollOrchestrationService.java` — one `getTickers` call per adapter; `recordSuccess` still per symbol

Missing symbols in a batch response log a **per-symbol WARN**, not a venue failure.

### 1.3 Migration V4

`V4__add_new_assets.sql` inserts:

- `SOL/USD`, `XRP/USD`, `DOGE/USD`
- `SOL/USDT`, `XRP/USDT`, `DOGE/USDT`, `BNB/USDT`

Comment documents why BNB has no USD row.

### 1.4 Config

`application.properties` extended for all probed markets across five venues. Absence of an entry means the venue does not list that market.

### 1.5 Symbol coverage in snapshots

**DTO:** `SymbolCoverageDto(symbol, quoteAsset, configuredVenues, freshVenues)`

Added to `SpreadSnapshotDto` and populated in `SpreadPublisher` from `ExchangeProperties` + `ExchangeAvailabilityStore.countFreshForSymbol`.

Frontend uses coverage for thin-market empty states (“Only 1 venue reporting — needs 2”).

### 1.6 MarketConfigValidator extensions

Startup WARNs for:

- Symbols offered by fewer than 2 venues (can never produce a spread)
- Active `tracked_pair` rows with no venue config
- Configured markets with no matching active DB row

Injected `TrackedPairRepository` for DB-side checks.

### 1.7 Tests & fixtures

- `fixtures/binance/batch-tickers.json`
- `fixtures/kraken/batch-tickers.json`
- `fixtures/bitget/batch-tickers-partial.json` (configured symbol missing)
- `fixtures/kucoin/batch-tickers.json`
- `BinanceAdapterTest` — batch + unsupported symbol never hits HTTP
- `MarketConfigValidatorTest` — drift / under-covered cases
- `SpreadCalculationServiceTest.testManySymbols_noCrossSymbolContamination`

### 1.8 Architecture doc

[ARCHITECTURE.md](../ARCHITECTURE.md#exchange-api-limits) updated with post-batching req/s per venue.

---

## Phase 2 — Settings

### 2.1 SettingsService

**File:** `frontend/src/app/services/settings.service.ts`

- Root-provided, signal-backed, `localStorage` key `crypto-arbitrage-monitor:settings`
- `mergeSettings()` drops unknown keys and resets on bad `version`
- Theme applied via `data-theme` on `<html>` (`system` honours `prefers-color-scheme`)

**Shape:**

```ts
interface DashboardSettings {
  version: 1;
  disabledExchanges: string[];
  disabledSymbols: string[];
  minNetSpreadPercent: number;
  hideBelowThreshold: boolean;
  theme: 'system' | 'light' | 'dark';
  density: 'comfortable' | 'compact';
  freshnessWindowMsOverride: number | null;
  defaultNotionalOverride: number | null;
}
```

Quote-asset toggle stays in `QuoteAssetService` (data universe, not a preference).

### 2.2 Settings drawer

**Files:** `components/settings-drawer/settings-drawer.component.{ts,html}`

Gear icon in sticky header opens a right slide-over with sections: Venues, Markets (grouped by quote), Opportunities (threshold + dim/hide), Appearance, Advanced overrides, Reset.

### 2.3 Filter pipeline

**File:** `frontend/src/app/utils/dashboard-filter.ts`

Ordered chain in `DashboardComponent`:

```
matrix → quote asset → drop disabled venue legs → drop disabled symbols → threshold (dim/hide) → visibleMatrix
```

When any venue is disabled, **best-per-symbol is recomputed** from `visibleMatrix` instead of using the backend value.

### 2.4 LIVE badge agreement

`ConnectionStatusComponent` calls `computeVisibleLive()` over visible venues when filters are active; falls back to backend `liveByQuote` when nothing is disabled.

Hidden venues render as muted “hidden” chips.

### 2.5 Guardrails & filter visibility

- Warning when fewer than 2 venues visible for selected quote
- Warning when all symbols hidden
- Filter chip row + “showing N of M routes” above matrix

### 2.6 Tests

`settings.service.spec.ts` — merge defaults, filter pipeline, badge recompute, mirror collapse.

---

## Phase 3 — Dashboard UX

### 3.1 Layout

**File:** `components/dashboard/dashboard.component.{ts,html}`

- **Sticky header** — title, LIVE badge, last-update age, quote toggle, compact notional, gear
- **KPI row** — best net spread, positive routes, venues fresh/total, symbols covered
- **Top Opportunities** — globally ranked table (symbol · route · net % · est. profit)
- **Full matrix** — full width, grouped by symbol, optional “show both directions”
- **Footer** — indicative-comparison disclaimer (closes Sprint 2 open item)

### 3.2 Opportunity ranking

- Global sort by net spread descending
- Mirrored routes collapsed by default (`collapseMirroredRoutes`); matrix toggle to show both
- Quick-filter chips: All / Positive net / Above threshold

### 3.3 Number readability

**File:** `utils/format-numbers.ts`

- `tabular-nums` via global CSS class
- Magnitude-aware price decimals (`priceDecimals`)
- Signed/coloured percentages; quote-aware profit labels (USD vs USDT, not hardcoded `$`)

### 3.3b Flash-on-change

**Files:** `directives/flash-on-change.directive.ts`, `styles.css` (`.flash-change`)

- `appFlashOnChange` directive restarts a brief background pulse when the bound value changes
- Wired on matrix buy/sell/raw/net cells, accordion best %, and Top Opportunities net/profit/prices
- Animation suppressed when `prefers-reduced-motion: reduce`

### 3.4 Theme and density

**File:** `styles.css` — semantic CSS variables + component classes (`panel`, `bg-page`, `chip-*`, dark via `data-theme` and `prefers-color-scheme`).

Density maps to row padding / font size on matrix and opportunities table.

### 3.5 Empty states

Distinct states in spread-detail and spread-table:

- **Loading** — skeleton rows
- **Filtered empty** — names filter, offers clear
- **No data** — uses `SymbolCoverage` when fewer than 2 fresh venues

### 3.6 Performance & a11y

- `ChangeDetectionStrategy.OnPush` on dashboard, connection-status, spread-detail, spread-table, settings-drawer
- Stable composite `track` keys on all `@for` loops
- Derived state in `computed()` signals
- `aria-live="polite"` on LIVE badge only (not streaming numbers)
- Settings drawer: Esc closes; focus returns to gear button

---

## Tracked universe after Sprint 3

| Symbol | Venues (configured) |
|---|---|
| BTC/USD, ETH/USD, SOL/USD | Binance*, Kraken, Coinbase |
| XRP/USD, DOGE/USD | Kraken, Coinbase |
| BTC/USDT … ETH/USDT, SOL/USDT, XRP/USDT, DOGE/USDT | Binance, Kraken, Coinbase, Bitget, KuCoin |
| BNB/USDT | Binance, Bitget, KuCoin |

\*Binance USD spot on `api.binance.com` verified for BTC, ETH, SOL only.

**11 active `tracked_pair` rows** total.

---

## Verification

```powershell
# Backend (JDK 17 required)
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot'
cd backend
.\gradlew.bat test --tests "com.cryptoarbitrage.monitor.exchange.*" --tests "com.cryptoarbitrage.monitor.service.*" --tests "com.cryptoarbitrage.monitor.config.*"

# Frontend
cd frontend
npm run build
npm test -- --watch=false --browsers=ChromeHeadless
```

**Smoke (with Postgres + backend running):**

```bash
curl -s localhost:8081/api/pairs | jq '.[].symbol'
# Expect 11 symbols including SOL/USD, BNB/USDT, etc.

curl -s localhost:8081/api/spreads/latest | jq 'length'
# Best-per-symbol rows after first poll cycle
```

Open `http://localhost:4200` — KPI row, ranked opportunities, settings gear, filter chips, dark mode toggle.

---

## Known limitations (unchanged)

- No Docker full-stack / Nginx (Sprint 4)
- Settings are client-side only (`localStorage`)
- Coinbase remains the per-product bottleneck at 5 req/cycle for USD symbols

**Done later (documented in [ARCHITECTURE.md](../ARCHITECTURE.md#error-handling)):** HTTP 429/418 and timeout backoff via `ExchangeBackoffFilter` + `ExchangeBackoffStore`; poll cycle skips venues still in the backoff window.

---

## Out of scope (deferred)

Alerts/notifications, historical charting, order-book depth, withdrawal/network fees, trade execution, server-side preferences — per [SPRINT3-PLAN.md](./SPRINT3-PLAN.md).
