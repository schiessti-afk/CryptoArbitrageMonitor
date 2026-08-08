# Sprint 3 Plan — Asset expansion, settings, and dashboard UX

**Goal:** Grow the tracked universe from 2 assets to 6, put the dashboard under user control, and
raise the UI to a state-of-the-art monitoring surface where the opportunities on screen are the ones
worth looking at.

**Exit criteria (from [SPRINT.md](./SPRINT.md)):** SOL, XRP, DOGE and BNB stream live alongside BTC
and ETH without raising per-venue request rate above the Sprint 1 budget; a settings panel can hide
any venue or market and the whole dashboard — cards, matrix, status chips and LIVE badge — reflects
that consistently; the default view still shows everything.

**Shipping V1 (Docker Compose, Nginx, 429 backoff, DoD) moved to [Sprint 4](./SPRINT.md#sprint-4--ship-v1).**

---

## Decisions taken before planning

| Decision | Choice | Consequence |
|---|---|---|
| Asset scope | Every venue that lists it, both quote universes | 11 tracked symbols; BNB is USDT-only |
| Settings scope | Client-side view filter, `localStorage` | No migration, no endpoints, no shared-state surprises; backend keeps polling everything |
| Extra settings | Min net-spread threshold, dark mode + density, freshness/notional overrides | Alerts and notifications deferred |

BNB is the constraint that shaped the first row: neither Coinbase nor Kraken lists it, so `BNB/USD`
would have at most one venue and can never produce a cross-venue comparison. It is tracked as
`BNB/USDT` only, across Binance, Bitget and KuCoin.

---

## Phase 1 — Asset universe expansion

**Deliverable:** SOL, XRP, DOGE and BNB polled, compared and streamed, with request rate per venue no
higher than today.

### 1.1 Probe the native symbols before writing any config (gate)

`application.properties` is the single source of truth for which venue lists which market
([ExchangeProperties](../backend/src/main/java/com/cryptoarbitrage/monitor/config/ExchangeProperties.java)),
so a wrong entry becomes a permanent WARN in every poll cycle. The Bitget/KuCoin entries were added
only after live probes — do the same here. Nothing in 1.3 or 1.4 is written until this table is
filled in from real responses.

```bash
# Binance — note this also re-checks the existing USD claim, see 1.2
curl -s "https://api.binance.com/api/v3/bookTicker?symbol=SOLUSDT" | jq
curl -s "https://api.binance.com/api/v3/bookTicker?symbol=SOLUSD"  | jq
# Kraken — Dogecoin is XDG on Kraken, not DOGE
curl -s "https://api.kraken.com/0/public/Ticker?pair=XDGUSD" | jq '.error, (.result | keys)'
curl -s "https://api.kraken.com/0/public/Ticker?pair=XXRPZUSD,SOLUSD" | jq '.error, (.result | keys)'
# Coinbase
curl -s "https://api.exchange.coinbase.com/products/DOGE-USD/ticker" | jq
curl -s "https://api.exchange.coinbase.com/products/BNB-USD/ticker"  | jq   # expect not-found
# Bitget / KuCoin
curl -s "https://api.bitget.com/api/v2/spot/market/tickers?symbol=BNBUSDT" | jq
curl -s "https://api.kucoin.com/api/v1/market/orderbook/level1?symbol=BNB-USDT" | jq
```

Record the **exact response key** for each, not just the request symbol — Kraken already burned us
once here (`pair=BTCUSDT` returns a result keyed `XBTUSDT`, documented at
[application.properties:39](../backend/src/main/resources/application.properties)). Expect the same
class of surprise for `XDG`.

Expected outcome, to be confirmed or corrected by the probe:

| Asset | Binance | Kraken | Coinbase | Bitget | KuCoin |
|---|---|---|---|---|---|
| SOL/USD | ? (see 1.2) | `SOLUSD` | `SOL-USD` | — | — |
| XRP/USD | ? | `XXRPZUSD` | `XRP-USD` | — | — |
| DOGE/USD | ? | `XDGUSD` | `DOGE-USD` | — | — |
| SOL/USDT | `SOLUSDT` | `SOLUSDT` | `SOL-USDT` | `SOLUSDT` | `SOL-USDT` |
| XRP/USDT | `XRPUSDT` | `XRPUSDT` | `XRP-USDT` | `XRPUSDT` | `XRP-USDT` |
| DOGE/USDT | `DOGEUSDT` | `XDGUSDT`? | `DOGE-USDT` | `DOGEUSDT` | `DOGE-USDT` |
| BNB/USDT | `BNBUSDT` | — | — | `BNBUSDT` | `BNB-USDT` |

### 1.2 Open question the probe must settle: Binance USD markets

`exchange.adapters.binance.markets.BTC_USD.native-symbol=BTCUSD` is configured against
`api.binance.com`. Binance's global spot venue is not generally understood to list USD-quoted spot
pairs — those live on `api.binance.us`. If `BTCUSD` is in fact returning an error today, Binance has
been silently absent from every USD comparison since Sprint 1, the USD universe has really only been
Kraken + Coinbase (exactly 2 venues, one route), and adding three more USD assets multiplies a
broken assumption.

The probe above answers it. Two outcomes:

- **Resolves:** nothing to do, add the three USD entries as planned.
- **404s:** remove the two USD entries from the Binance block, and the USD universe is honestly a
  two-venue comparison. That makes Phase 1.6 (thin-market handling) load-bearing rather than
  defensive, and it is worth surfacing on the dashboard in Phase 3 rather than leaving users to
  assume five venues are being compared.

Either way this is a finding to record in the implementation doc, not something to paper over.

### 1.3 Batch the ticker fetch per venue (do before adding symbols)

[`PollOrchestrationService.fetchTickersInParallel`](../backend/src/main/java/com/cryptoarbitrage/monitor/service/PollOrchestrationService.java)
issues one HTTP request per `(symbol, venue)` pair. Today that is ~12 requests per 3s cycle. At 11
symbols it becomes ~45 per cycle — 15 req/s spread across five venues, with the per-venue peak
landing in a burst at the top of each cycle.

Four of the five venues expose a batch form; adopt them before the symbol count grows:

| Venue | Batch endpoint | Requests/cycle after |
|---|---|---|
| Binance | `/api/v3/ticker/bookTicker?symbols=["A","B",…]` | 1 |
| Kraken | `/0/public/Ticker?pair=A,B,C` (comma-separated) | 1 |
| Bitget | `/api/v2/spot/market/tickers` (all symbols) | 1 |
| KuCoin | `/api/v1/market/allTickers` (all symbols) | 1 |
| Coinbase | no batch best-bid/ask exists; `/products/{id}/ticker` stays per-product | 5 (USD symbols only) |

Total drops from ~45 to ~9 requests per cycle — **below today's 12**, while tracking nearly three
times the symbols. Coinbase's per-product limit is the tightest of the five and 5 req/3s sits
comfortably inside it.

**Interface change** — add a batch method to
[`ExchangeAdapter`](../backend/src/main/java/com/cryptoarbitrage/monitor/exchange/ExchangeAdapter.java)
with a default implementation, so no adapter is forced to change and Coinbase simply keeps the
default:

```java
default Flux<PriceTicker> getTickers(Collection<String> internalSymbols) {
    return Flux.fromIterable(internalSymbols)
            .filter(this::supports)
            .flatMap(this::getTicker);
}
```

`getTicker(String)` stays — it is what the existing adapter tests drive. `PollOrchestrationService`
then calls `getTickers` once per adapter with the full active-symbol list instead of building a
per-symbol request list.

Two behaviours the batch implementations must preserve, both currently guaranteed by the
per-symbol path:

- `availabilityStore.recordSuccess(exchange, symbol)` is still recorded **per symbol**, not per
  request — otherwise one venue returning a partial batch reads as fully fresh.
- A symbol absent from a batch response is a per-symbol failure, not a venue failure. The all-symbol
  endpoints (Bitget, KuCoin) return hundreds of markets; filter to the configured set and treat a
  missing configured symbol as a WARN for that symbol alone.

### 1.4 Migration `V4__add_new_assets.sql`

Seven new `tracked_pair` rows:

```
SOL/USD, XRP/USD, DOGE/USD        -- only if 1.2 leaves ≥2 venues on USD
SOL/USDT, XRP/USDT, DOGE/USDT, BNB/USDT
```

No `exchange_fee` rows — fees are per venue, and all five venues are already seeded. Follow the V3
comment convention and record *why* BNB has no USD row, so the next person does not "fix" it.

`spread_log.symbol` is `VARCHAR(20)` — all new symbols fit.

### 1.5 Config entries

Extend `exchange.adapters.*.markets.*` in `application.properties` with the probed native symbols.
Keep the existing convention: absence of an entry means the venue does not list the market, and
never write an entry for a market that failed its probe.

### 1.6 Thin-market handling (new)

With 4 symbols this never mattered. With 11 it will: a symbol reporting from fewer than 2 venues
produces zero matrix rows, so its card and table section simply **vanish** — indistinguishable from
"loading" or "we forgot to add it". `BNB/USDT` on three venues is one venue outage away from this,
and every USD symbol is if 1.2 resolves badly.

Add per-symbol coverage to the published snapshot in
[`SpreadPublisher`](../backend/src/main/java/com/cryptoarbitrage/monitor/service/SpreadPublisher.java):

```java
record SymbolCoverageDto(String symbol, String quoteAsset, int configuredVenues, int freshVenues) {}
```

Derived from `ExchangeProperties` (configured) and `ExchangeAvailabilityStore.countFreshForSymbol`
(fresh) — both already exist. The frontend uses it in Phase 3 to render an honest empty state
("Only Kraken reporting — a cross-venue comparison needs 2") instead of nothing.

### 1.7 Extend `MarketConfigValidator`

[`MarketConfigValidator`](../backend/src/main/java/com/cryptoarbitrage/monitor/config/MarketConfigValidator.java)
today catches only mixed quote assets. Two new startup checks, both cheap and both catching real
Phase-1 failure modes:

- **Under-covered symbol** — a configured symbol offered by fewer than 2 venues can never yield a
  spread. WARN at startup rather than discovering it as a silently missing card.
- **Config/DB drift** — a configured market with no matching active `tracked_pair` row is never
  polled; an active `tracked_pair` row with no venue config is polled by nobody. The two lists are
  maintained in different files and *will* drift. WARN both directions.

### 1.8 Tests

- Batch-response fixtures per venue under `src/test/resources/fixtures/{venue}/`, captured from the
  1.1 probes — including one partial batch (configured symbol missing from the response).
- Default `getTickers` fan-out behaves identically to the per-symbol path (Coinbase's path).
- `MarketConfigValidator` fires each new warning on a crafted config.
- `SpreadCalculationServiceTest` extended to many symbols, asserting no cross-symbol contamination
  in `bestPerSymbol`.
- A test that an unsupported symbol never reaches the HTTP layer (existing `supports` contract,
  now exercised through the batch path).

### 1.9 Documentation

Update the exchange API-limit summary in [ARCHITECTURE.md](./ARCHITECTURE.md) with post-batching
requests/second per venue, so Sprint 4's 429-backoff work starts from a real number.

### Phase 1 exit criteria

All 11 symbols appear in `/api/spreads/latest` within one cycle of startup; per-venue request rate
is at or below the Sprint 1 figure; no venue logs a WARN for a market it does not list.

---

## Phase 2 — Settings

**Deliverable:** a gear icon opening a settings drawer that can hide venues and markets, filter by
minimum net spread, and switch theme and density — all persisted locally, all defaulting to
"show everything".

### 2.1 `SettingsService`

Root-provided Angular service, signal-backed, `localStorage`-persisted, modelled on the existing
[`QuoteAssetService`](../frontend/src/app/services/quote-asset.service.ts) (same try/catch around
storage so private browsing degrades silently).

```ts
interface DashboardSettings {
  version: 1;
  disabledExchanges: string[];        // [] = all shown
  disabledSymbols: string[];          // [] = all shown
  minNetSpreadPercent: number;        // 0
  hideBelowThreshold: boolean;        // false — dim, don't hide, until asked
  theme: 'system' | 'light' | 'dark'; // 'system'
  density: 'comfortable' | 'compact'; // 'comfortable'
  freshnessWindowMsOverride: number | null;   // null = use /api/config
  defaultNotionalOverride: number | null;     // null = use /api/config
}
```

Read path merges the stored blob over defaults and drops unknown keys, so a stale or hand-edited
blob can never break the dashboard. `version` exists so a future shape change can migrate rather
than reset.

Storage keys stay namespaced under `crypto-arbitrage-monitor:` alongside the existing quote-asset
key; the quote-asset selection is deliberately **left where it is** rather than folded in — it is a
data-universe choice, not a preference, and it belongs in the header toggle.

### 2.2 The panel

A right-side slide-over drawer, not a modal — the dashboard stays visible so every toggle's effect
is seen immediately. Gear icon in the header next to the LIVE badge.

Sections:

| Section | Contents |
|---|---|
| Venues | One switch per venue, with its fresh/stale dot and offered quote assets inline |
| Markets | One switch per tracked symbol, grouped by quote asset |
| Opportunities | Min net-spread % input; "dim" vs "hide" radio |
| Appearance | Theme (system/light/dark); density (comfortable/compact) |
| Advanced | Freshness-window and default-notional overrides, each showing the backend value as placeholder |
| — | "Reset to defaults" |

### 2.3 One filter pipeline, not filters scattered per component

Today [`DashboardComponent`](../frontend/src/app/components/dashboard/dashboard.component.ts)
filters by quote asset inline and passes results down. Adding three more filter dimensions that way
will drift. Consolidate into one ordered computed chain in the dashboard:

```
snapshot.matrix
  → quote asset (existing)
  → drop rows whose buy OR sell venue is disabled
  → drop rows whose symbol is disabled
  → threshold (dim or hide)
  → visibleMatrix
```

**The subtle part:** `bestPerSymbol` arrives precomputed by the backend across *all* venues. Disable
the venue sitting in the winning route and the card would keep showing a route the user has hidden —
a filter that lies. Whenever any venue is disabled, the frontend must **recompute best-per-symbol
from `visibleMatrix`** rather than use the backend's. With no venues disabled, use the backend value
unchanged so the common path is untouched.

### 2.4 The LIVE badge has to agree with the filter

The badge reads `snapshot.liveByQuote[selected]`, computed backend-side over all venues
([connection-status.component.ts:43](../frontend/src/app/components/connection-status/connection-status.component.ts)).
Disable three of five venues and the backend still reports LIVE while the user is looking at one
venue. Recompute the badge client-side over *visible* venues (≥2 fresh among visible), falling back
to the backend value when nothing is disabled. Same principle as 2.3: what the badge describes must
be what is on screen.

Venue chips in the status bar hide alongside, and disabled venues get a muted "hidden" chip rather
than disappearing without trace — see 2.5.

### 2.5 Guardrails

Nothing stops a user disabling every venue. Each of these needs an explicit state, not a blank
panel:

- **Fewer than 2 venues visible for the selected quote asset** — inline warning: no spread can be
  computed at all, with a one-click "show all venues".
- **All symbols hidden** — empty state naming the count and offering the same escape.
- **Filters active** — a persistent, dismissible chip row above the matrix ("3 venues hidden",
  "net ≥ 0.2%") plus a "showing 34 of 70 routes" count. The rule: never hide data without a visible
  marker of what is hidden and a one-click way to clear it. This is what keeps the settings drawer
  from becoming a place where state goes to be forgotten.

### 2.6 Overrides are overrides

`freshnessWindowMsOverride` and `defaultNotionalOverride` start `null`, meaning "follow
`/api/config`". Show the backend value as the input placeholder and mark the field when it diverges,
so it is always clear whether a number is yours or the server's. The freshness override affects only
the client-side freshness/badge rendering — the backend's own staleness accounting is unchanged.

### 2.7 Tests

- Merge-over-defaults: missing keys, unknown keys, corrupt JSON, absent `localStorage`.
- Filter pipeline: a row is excluded when *either* leg is disabled.
- Best-per-symbol recomputation matches the backend's when nothing is disabled, and diverges
  correctly when the winning route's venue is hidden.
- Badge recomputation under partial venue hiding.

### Phase 2 exit criteria

Default state shows exactly what Sprint 2 showed. Any venue or market can be hidden, and cards,
matrix, chips, badge and counts all agree with the filtered set. Settings survive reload; clearing
`localStorage` returns to defaults cleanly.

---

## Phase 3 — Dashboard UX

**Deliverable:** a dashboard where the opportunities that matter are ranked at the top, everything is
still reachable, and the numbers are readable at a glance while streaming.

### 3.1 Layout

Current structure puts a full-width card around a single notional input, squeezes a 7-column matrix
into a third of the width, and buries the ranking — the cards are one-per-symbol in arbitrary order,
so nothing tells you which of 11 symbols is worth looking at right now.

Restructure to:

- **Sticky header** — title, LIVE badge, last-update age, quote toggle, compact notional control,
  gear. Stays visible while scrolling the matrix; the health signal should never scroll away.
- **KPI row** — best net spread right now, count of positive routes, venues fresh/total, symbols
  covered. Four tiles, each a one-glance answer.
- **Opportunities (primary panel)** — ranked across *all* symbols by net spread descending. This is
  the answer to "the opportunities shown are the ones making sense": one ordered list, not 11
  unordered cards. Each row: symbol · buy venue → sell venue · net % · estimated profit at notional
  · age.
- **Full matrix (secondary, full width)** — sortable, grouped by symbol, with the filter chip row
  from 2.5 above it.
- **Footer** — the indicative-comparison disclaimer (3.8).

### 3.2 Making the opportunity list mean something

Three concrete changes, all defaulting to showing everything as requested:

**a. Rank globally.** Sort by net spread descending across symbols. Currently order comes from map
iteration.

**b. Collapse mirrored routes.** For every venue pair the matrix contains both A→B and B→A. They use
opposite sides of each book, so they are not exact negatives, but one direction is essentially always
the worse one — and it is currently shown with equal weight. At five venues that is 20 rows per
symbol where 10 carry the information. Default to the better direction per unordered venue pair,
with a "show both directions" toggle for anyone checking the math.

**c. Keep the classification honest.** The existing POTENTIAL / NEUTRAL / NO_OPPORTUNITY states in
[spread-state.ts](../frontend/src/app/utils/spread-state.ts) stay. Add quick-filter chips above the
list — **All** (default) / Positive net / Above threshold — so filtering is one click and its state
is visible, rather than living only in the settings drawer.

Negative best spreads remain visible by default: a symbol whose best route is −0.4% is real
information, and Sprint 2's exit criteria depend on it.

### 3.3 Number readability

The dashboard repaints every 3 seconds; most of the polish budget belongs here.

- **Tabular figures** (`font-variant-numeric: tabular-nums`) on every price and percentage, so digits
  stop jittering on each update.
- **Magnitude-aware precision.** `number:'1.2-8'` and `number:'1.2-4'` are hardcoded per template.
  BTC at 2dp is right; DOGE at `0.2100` throws away the digits that carry the spread. One helper
  keyed off price magnitude, used everywhere. This becomes visible the moment DOGE lands.
- **Signed, colored percentages** at consistent precision.
- **Quote-aware currency.** The matrix headers say "Buy $" / "Sell $", the notional label says
  "Investment Amount ($)", and profit renders `${{ … }}` — but a USDT market is not dollars. Render
  the selected quote asset throughout.
- **Flash-on-change** — a brief background pulse when a cell's value changes, so liveness is visible
  without reading the timestamp. Respect `prefers-reduced-motion`.

### 3.4 Theme and density

Phase 2 stores the setting; Phase 3 makes it look right. The templates currently hardcode
`bg-gray-50`, `bg-white`, `text-gray-600` and friends across five components. Introduce a semantic
token layer (Tailwind `dark:` variants driven by a `data-theme` attribute on `<html>`, honouring
`prefers-color-scheme` for `'system'`), then convert the components once. Density maps to row
padding and font size on the matrix.

Doing this centrally in one pass is why it sits here rather than in Phase 2 — converting five
components twice would be the expensive order.

### 3.5 Three empty states, not one

Every panel currently degrades to "Waiting for data..." or "No data yet" regardless of cause.
Distinguish:

- **Loading** — skeleton rows, not text.
- **Empty because filtered** — name the filter, offer to clear it.
- **Empty because no data** — name the reason using Phase 1.6 coverage: venue down, or fewer than
  two venues list this market.

### 3.6 Accessibility and responsive

- Colour is never the only signal — keep the emoji/label pairing already in place.
- `aria-live="polite"` on the LIVE badge; do **not** put it on the streaming numbers, which would
  make a screen reader unusable at 3-second intervals.
- Settings drawer: keyboard reachable, focus-trapped, Esc closes, focus returns to the gear.
- Visible focus rings throughout.
- Matrix scrolls horizontally on narrow screens with a sticky first column; touch targets ≥44px.

### 3.7 Rendering performance

11 symbols × 5 venues is up to ~220 matrix rows arriving every 3 seconds, roughly 5× today. Before
that lands: `ChangeDetectionStrategy.OnPush` on all components, stable composite `track` keys on
every `@for`, and all derived state in `computed()` signals so the filter chain runs once per
snapshot rather than once per binding. Re-measure after Phase 1; virtualize the matrix only if row
count actually exceeds ~200 after mirror-collapsing.

### 3.8 Copy

Move the indicative-comparison disclaimer onto the dashboard as a persistent footer — closing the
open Sprint 2 checklist item at [SPRINT.md:86](./SPRINT.md). It should name what the figure excludes:
withdrawal and network fees, transfer time, latency, and order-book depth. The estimated-profit model
is `notional × net% ÷ 100`, which assumes the full notional fills at the quoted top-of-book price on
both legs — worth a tooltip on the number itself.

### Phase 3 exit criteria

Best current opportunity across all symbols is identifiable within two seconds of loading the page;
every filter in effect is visible on the main surface; dark mode and both densities render correctly
across all panels; no panel renders an unexplained blank.

---

## Not in Sprint 3

Alerts and browser notifications (considered, deferred), historical charting, order-book depth,
withdrawal/network fee modelling, per-venue fee tiers, trade execution, user accounts, and any
server-side persistence of preferences. Docker Compose, Nginx, 429 backoff and the V1 DoD are
[Sprint 4](./SPRINT.md#sprint-4--ship-v1).

---

## Sequencing note

The phases are ordered by dependency, not preference:

- 1.3 (batching) must precede 1.4/1.5, or the symbol expansion lands as a request-rate spike.
- 1.6 (coverage data) is consumed by 3.5 (empty states).
- Phase 2 stores `theme`/`density`; Phase 3 implements them. Phase 2 is shippable without Phase 3 —
  the settings simply have no visual effect yet, which is worth stating in the Phase 2 demo.
- 2.3 and 2.4 (filter pipeline, badge agreement) are the correctness core of Phase 2. If Phase 2
  gets squeezed, cut settings, not those.
