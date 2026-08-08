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

- **Core (always polled):** existing USD + USDT products from Sprint 3 (`core-symbols` in `application.properties`).
- **Optional (expand):** ADA, AVAX, LINK, DOT, SHIB, NEAR, ATOM, OP USDT — polled only when the client reports **≤8 enabled markets** via `PUT /api/preferences/poll`.
- Frontend `PollPreferenceService` syncs enabled symbols whenever settings or the pair list changes.

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
curl -s localhost:8081/api/pairs | jq 'length'          # expect 31
curl -X PUT localhost:8081/api/preferences/poll \
  -H 'Content-Type: application/json' \
  -d '{"enabledSymbols":["ADA/USDT","LINK/USDT"]}'
```
