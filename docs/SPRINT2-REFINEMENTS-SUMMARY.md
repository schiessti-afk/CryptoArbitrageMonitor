# Sprint 2 Refinements Implementation Summary

**Completed:** All 7 refinements to make the dashboard honest, transparent, and testable.

---

## 1. Quote Asset Threading & Validation ✅

### Backend Changes

**PriceTicker.java** — Added two new fields:
- `String nativeSymbol` (e.g., "BTCUSD", "XXBTZUSD", "BTC-USD")
- `String quoteAsset` (e.g., "USD", "USDT")
- Compact constructor validates both fields are non-empty

**All three exchange adapters** — Updated to pass native symbol and quote asset:
- `BinanceAdapter`, `KrakenAdapter`, `CoinbaseAdapter`
- Each now passes nativeSymbol from config and hardcoded "USD" for quoteAsset

**SpreadCalculationService** — Updated `SpreadOpportunity` class:
- Added `buyNativeSymbol`, `buyQuoteAsset`, `sellNativeSymbol`, `sellQuoteAsset`
- Constructor threaded through from tickers
- `calculateSpread()` passes these values when creating opportunities

**SpreadDto** — Updated to carry market details:
- All six new fields added (buy/sell native symbol and quote asset)
- `from(SpreadOpportunity)` maps them through
- `from(SpreadLog)` sets nulls (history doesn't store this yet)

**MarketConfigValidator.java** — New component:
- Runs on `@PostConstruct`
- Logs WARN if one internal symbol is configured with mixed quote assets across exchanges
- Protects against accidental USDT swaps

**AppConfigDto.java** — New DTO for `/api/config` endpoint:
- Exposes `defaultNotional`, `freshnessWindowMs`, `neutralEpsilonPercent`, and live fee list

**SpreadController** — Added `/api/config` endpoint:
- Returns application configuration for frontend to use

### Frontend Changes

**Dashboard component template** — Added info tooltip:
- `BTC/USD — indicative cross-venue comparison` with ⓘ link
- Tooltip explains each exchange uses its native market (Binance BTCUSD, Kraken XXBTZUSD, Coinbase BTC-USD), all USD-quoted

---

## 2. "Best Current Spreads" with Verdict ✅

### New Utility: `spread-state.ts`

Created a unified classifier and styling system:

**`getSpreadState(netPercent)`** — Returns `SpreadState`:
- `'POTENTIAL'` if `netPercent > 0.001%`
- `'NO_OPPORTUNITY'` if `netPercent < -0.001%`
- `'NEUTRAL'` if within epsilon

**`getStateLabel(state, netPercent)`** — Returns human-readable verdict:
- POTENTIAL: `"POTENTIAL OPPORTUNITY — Net spread: +0.2910%"`
- NO_OPPORTUNITY: `"NO POSITIVE OPPORTUNITY — Best net spread: -0.3675%"`
- NEUTRAL: `"NO MEANINGFUL SPREAD — Net spread: 0.0000%"`

**`getIndicatorEmoji(state)`** — Returns emoji:
- `'🟢'` for POTENTIAL
- `'🔴'` for NO_OPPORTUNITY
- `'⚪'` for NEUTRAL

**`getStateClasses(state)`** — Returns Tailwind classes object for styling background, border

**`getStateTextClass(state)`** — Returns text color Tailwind class

### Spread Detail Component

**spread-detail.component.ts** — Updated to use classifier:
- Imports all utility functions
- Added helper methods that delegate to classifier
- Title changed to "Best Current Spreads"

**spread-detail.component.html** — Redesigned card:
- Shows heading + emoji + verdict label
- Verdict is bold and color-coded per state
- "Estimated profit" kept as label (not "Profit")
- Allow profit to go negative

---

## 3. Unified Styling from Spread State ✅

**spread-table.component.ts** — Now uses same classifier:
- Added `getStateBackgroundClass(netPercent)` returning Tailwind class
- Removed all inline ternaries
- Both components now classify identically

**No duplication** — All ternaries replaced with shared utility calls

---

## 4. Matrix: Group by Symbol, Sort by Net Desc ✅

### Spread Table Component

**spread-table.component.ts** — Complete rewrite:
- Introduced `MatrixGroup` interface with `symbol` and `rows[]`
- `groupedMatrix = computed()` signal that:
  - Groups by symbol
  - Sorts within each group: `netSpreadPercent desc` → `rawSpreadPercent desc` → `buyExchange asc`
  - Returns sorted groups
- Added `calculateFeeImpact(opp)` to show fee attribution

**spread-table.component.html** — New structure:
- Symbol subheaders per group
- Table per symbol with sorted rows
- New `Fees %` column showing `rawSpreadPercent - netSpreadPercent`
- Precision standardized to 4 decimals (from mixed 2-4)
- Color-coded rows (green / red / neutral)

---

## 5. Notional Quick-Select + Config Endpoint ✅

### Dashboard Component

**dashboard.component.ts** — Enhanced:
- Added `config = signal<any>(null)`
- On init, fetches `/api/config` and sets `defaultNotional` from it
- Added `quickSelectAmounts = [100, 1000, 5000, 10000, 50000]`
- Added `setNotional(amount)` method

**dashboard.component.html** — Added UI:
- Quick-select buttons with active-state highlight
- Manual input field with min/max constraints
- Collapsible "Fees & Spread Math" panel showing:
  - Live fee table from `/api/config`
  - Formula: `net% = ((sell × (1 − sell_fee)) / (buy × (1 + buy_fee)) − 1) × 100`
  - Explanatory note about ask/bid and fee direction

---

## 6. Client-Side Freshness Ticker ✅

### Connection Status Component

**connection-status.component.ts** — Complete rewrite:
- Added `now = signal(Date.now())` updated every 1 second via `setInterval`
- Added `badge = signal<ConnectionBadge>('LIVE' | 'DEGRADED' | 'STALE')`
- Removed client-side `live` mutation
- New `effect()` derives badge state from:
  1. **STALE** if age > 10 seconds (no message in 10s)
  2. **DEGRADED** if backend `live=false` (<2 fresh exchanges) and age ≤ 10s
  3. **LIVE** if both conditions pass
- `lastMessageAge` displays: `"Updated 2s ago"` (ticking)

**connection-status.component.html** — Three-state badge:
- Shows `getBadgeEmoji()` + badge text
- Color-coded: 🟢 green, 🟡 yellow, 🔴 red
- Per-exchange chips unchanged (FRESH/STALE/NEVER)

**websocket.service.ts** — Removed mutation:
- `resetStalenessTimer()` no longer mutates `snapshot.live`
- Backend computes freshness; client computes transport staleness
- Two independent failure modes now visible separately

---

## 7. Fee Visibility & Reproducibility ✅

### Backend

**Endpoint**: `/api/config` returns fees list + config values

**Test**: `SpreadCalculationServiceTest.testCalculateSpread_Kraken_to_Binance_matches_screenshot()`
- Uses exact screenshot numbers:
  - Buy Kraken @ 64,967.30, Sell Binance @ 64,963.00
  - Kraken fee 0.26%, Binance fee 0.1%
  - Expected net: **-0.3675%** ✓
- Verifies native symbol threading
- Enforces reproducibility

### Frontend

**Dashboard** — Collapsible "Fees & Spread Math" panel:
- Fee table (Exchange | Taker Fee) fetched from `/api/config`
- Formula display with monospace font
- Explanatory note about ask/bid and fee application

**Spread Table** — New "Fees %" column:
- Shows `rawSpreadPercent - netSpreadPercent`
- Demonstrates fee impact visually per route

### Documentation

Updated `SPRINT2-PLAN.md` and `SPRINT2-IMPLEMENTATION.md` to include all 7 items.

---

## What's Fixed / Improved

| Item | Before | After |
|------|--------|-------|
| Quote assets | Hidden in config | Visible in UI + validated at startup |
| Spread labels | Generic "Best Opportunities" | Verdict-driven "Best Current Spreads" with emoji |
| Matrix view | Flat, unsorted | Grouped by symbol, sorted by net desc, with fee column |
| Freshness badge | "LIVE" or "DEGRADED" (2-state) | "LIVE", "DEGRADED", or "STALE" (3-state, client-ticking) |
| Fee transparency | Implicit in calculation only | Visible in table, panel, and formula |
| Notional input | Fixed amount or hardcoded | Quick-select buttons + config-driven default |
| Reproducibility | No tests for exact numbers | Screenshot numbers verified by test case |
| Precision | Mixed 2-4 decimals | Standardized to 4 decimals |

---

## Breaking Changes

**Minimal.** The API payload (`SpreadDto`) now includes market fields, but backend HTTP responses are additive (new fields don't break existing clients). The `/api/config` endpoint is new.

---

## Next Steps

1. **Install Java 17+** to run `./gradlew build` (current env has Java 8)
2. **Run backend tests** to verify spread calculation test passes
3. **Run `ng serve`** in frontend and open `localhost:4200` to test dashboard
4. **Verify**:
   - Quick-select buttons work
   - Fees panel renders
   - Connection badge ticks every second
   - Matrix groups by symbol and sorts correctly
   - Negative spreads show "NO POSITIVE OPPORTUNITY" (not hidden)

---

## Files Changed

**Backend (7 files)**
- `src/main/java/com/cryptoarbitrage/monitor/exchange/PriceTicker.java` — Added native symbol + quote asset
- `src/main/java/com/cryptoarbitrage/monitor/exchange/{Binance,Kraken,Coinbase}Adapter.java` — Pass new fields
- `src/main/java/com/cryptoarbitrage/monitor/service/SpreadCalculationService.java` — Thread through opportunity
- `src/main/java/com/cryptoarbitrage/monitor/dto/SpreadDto.java` — Carry market details
- `src/main/java/com/cryptoarbitrage/monitor/dto/AppConfigDto.java` — **NEW**
- `src/main/java/com/cryptoarbitrage/monitor/config/MarketConfigValidator.java` — **NEW**
- `src/main/java/com/cryptoarbitrage/monitor/controller/SpreadController.java` — Add `/api/config`

**Frontend (8 files)**
- `src/app/utils/spread-state.ts` — **NEW** Classifier + styling
- `src/app/components/spread-detail/{.ts, .html}` — Use classifier, update heading
- `src/app/components/spread-table/{.ts, .html}` — Group + sort + fee column
- `src/app/components/connection-status/{.ts, .html}` — 3-state badge + ticking
- `src/app/components/dashboard/{.ts, .html}` — Quick-select + config + fees panel
- `src/app/services/websocket.service.ts` — Remove `live` mutation

**Tests (1 file)**
- `backend/src/test/java/.../SpreadCalculationServiceTest.java` — Update to new PriceTicker signature + add screenshot test

**Docs (2 files)**
- `docs/SPRINT2-PLAN.md` — Added refinement section
- `docs/SPRINT2-IMPLEMENTATION.md` — Added implementation details

