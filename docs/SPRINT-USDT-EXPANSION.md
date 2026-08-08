# USDT market expansion — implementation notes

**Date:** 2026-08-08  
**Migration:** `V5__add_usdt_assets.sql` — 20 new `*/USDT` rows (31 USDT symbols total with Sprint 3)

## Live probe summary

Probed Binance, Kraken, Coinbase, Bitget, KuCoin on 2026-08-08. Binance API returned HTTP 451 from the probe environment; symbols follow Bitget/KuCoin conventions (standard `{BASE}USDT`).

| Asset | Binance | Kraken | Coinbase | Bitget | KuCoin |
|---|---|---|---|---|---|
| ADA | ADAUSDT* | ADAUSDT | ADA-USDT | ADAUSDT | ADA-USDT |
| AVAX | AVAXUSDT* | AVAXUSDT | AVAX-USDT | AVAXUSDT | AVAX-USDT |
| LINK | LINKUSDT* | LINKUSDT | LINK-USDT | LINKUSDT | LINK-USDT |
| SUI | SUIUSDT* | — | — | SUIUSDT | SUI-USDT |
| DOT | DOTUSDT* | DOTUSDT | DOT-USDT | DOTUSDT | DOT-USDT |
| TON | TONUSDT* | TONUSDT | — | — | TON-USDT |
| LTC | LTCUSDT* | LTCUSDT | — | LTCUSDT | LTC-USDT |
| BCH | BCHUSDT* | BCHUSDT | — | BCHUSDT | BCH-USDT |
| SHIB | SHIBUSDT* | SHIBUSDT | SHIB-USDT | SHIBUSDT | SHIB-USDT |
| PEPE | PEPEUSDT* | — | — | PEPEUSDT | PEPE-USDT |
| UNI | UNIUSDT* | — | — | UNIUSDT | UNI-USDT |
| NEAR | NEARUSDT* | — | NEAR-USDT | NEARUSDT | NEAR-USDT |
| APT | APTUSDT* | — | — | APTUSDT | APT-USDT |
| ATOM | ATOMUSDT* | ATOMUSDT | ATOM-USDT | ATOMUSDT | ATOM-USDT |
| FIL | FILUSDT* | — | — | FILUSDT | FIL-USDT |
| ARB | ARBUSDT* | — | — | ARBUSDT | ARB-USDT |
| OP | OPUSDT* | — | OP-USDT | OPUSDT | OP-USDT |
| INJ | INJUSDT* | — | — | INJUSDT | INJ-USDT |
| AAVE | AAVEUSDT* | — | — | AAVEUSDT | AAVE-USDT |
| WIF | WIFUSDT* | — | — | WIFUSDT | WIF-USDT |

\*Binance not live-verified in probe environment (geo-block); configured to match Bitget.

All 20 rows meet the ≥2 venue rule among Binance/Kraken/Bitget/KuCoin.

## Coinbase polling

- **Per-cycle budget:** at most **8** product ticker calls (`max-products-per-cycle` in `application.properties`).
- **Priority:** configured **core symbols** first (USD + major USDT from Sprint 3), then other enabled Coinbase markets in **client enable order**; symbols beyond the budget get no Coinbase ticker that cycle (other venues still poll them).
- Frontend `PollPreferenceService` syncs enabled symbols in enable order via `PUT /api/preferences/poll` whenever settings or the pair list changes.

## Files touched

| Area | Files |
|---|---|
| DB | `V5__add_usdt_assets.sql` |
| Config | `application.properties` |
| Backend | `ExchangeProperties`, `CoinbasePollSymbolResolver`, `ClientPollPreferenceService`, `PollOrchestrationService`, `SpreadController`, `AppConfigDto`, `PollPreferenceDto` |
| Frontend | `poll-preference.service.ts`, `settings-drawer`, `dashboard`, `spread.model.ts` |
| Tests | `CoinbasePollSymbolResolverTest`, `BinanceAdapterTest` fixtures |
| Docs | `README.md`, `ARCHITECTURE.md`, `SPRINT.md` |

## Verification

```bash
cd backend && ./gradlew test
cd frontend && npm test -- --watch=false
curl -s localhost:8081/api/pairs | jq 'length'          # expect 50 after V6
curl -X PUT localhost:8081/api/preferences/poll \
  -H 'Content-Type: application/json' \
  -d '{"enabledSymbols":["ADA/USDT","LINK/USDT"]}'
```

---

## Batch 2 (V6) — 2026-08-08

**Migration:** `V6__add_usdt_assets_batch2.sql` — 19 additional `*/USDT` rows (45 USDT / 50 total with USD).

| Asset | Binance | Kraken | Coinbase | Bitget | KuCoin |
|---|---|---|---|---|---|
| TRX | TRXUSDT | — | — | TRXUSDT | TRX-USDT |
| POL | POLUSDT | — | — | POLUSDT | POL-USDT |
| ETC | ETCUSDT | — | — | ETCUSDT | ETC-USDT |
| ALGO | ALGOUSDT | ALGOUSDT | — | ALGOUSDT | ALGO-USDT |
| VET | VETUSDT | VETUSDT | — | VETUSDT | VET-USDT |
| ICP | ICPUSDT | — | — | ICPUSDT | ICP-USDT |
| HBAR | HBARUSDT | — | HBAR-USDT | HBARUSDT | HBAR-USDT |
| SEI | SEIUSDT | — | — | SEIUSDT | SEI-USDT |
| TIA | TIAUSDT | — | — | TIAUSDT | TIA-USDT |
| STX | STXUSDT | — | STX-USDT | STXUSDT | STX-USDT |
| RUNE | RUNEUSDT | — | — | RUNEUSDT | RUNE-USDT |
| JUP | JUPUSDT | — | — | JUPUSDT | JUP-USDT |
| WLD | WLDUSDT | — | — | WLDUSDT | WLD-USDT |
| FET | FETUSDT | — | FET-USDT | FETUSDT | FET-USDT |
| RENDER | RENDERUSDT | — | — | RENDERUSDT | RENDER-USDT |
| TAO | TAOUSDT | — | — | TAOUSDT | TAO-USDT |
| ENA | ENAUSDT | — | — | ENAUSDT | ENA-USDT |
| ONDO | ONDOUSDT | — | — | ONDOUSDT | ONDO-USDT |
| PENDLE | PENDLEUSDT | — | — | PENDLEUSDT | PENDLE-USDT |

**Notes:**
- **POL** replaces requested `MATIC/USDT` — Bitget/KuCoin list POL only; Binance still has legacy `MATICUSDT` but cross-venue arb needs POL.
- **MKR/USDT** not added — Binance-only among configured venues (no ≥2-venue spread).
