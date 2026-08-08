# Sprint 4 Implementation — Ship V1

**Status:** Implemented and verified (2026-08-08). `docker compose up --build` starts postgres + backend + Nginx SPA; API and SockJS proxy smoke-tested.

Plan reference: [SPRINT4-PLAN.md](./SPRINT4-PLAN.md)

---

## Overview

Sprint 4 ships the production-like local stack: multi-stage Docker images for backend and frontend, Nginx SPA/API/WebSocket routing, Compose wiring with healthchecks, and a small hardening pass on CORS, logging, and API error JSON.

Backoff / fee / exchange URL externalization landed earlier and were left in place.

---

## Phase 1 — Full-stack Docker Compose

### Layout

| Path | Role |
|---|---|
| `backend/Dockerfile` | JDK 17 Gradle `bootJar` → JRE 17 Alpine + `curl` healthcheck |
| `backend/.dockerignore` | Excludes `.gradle/`, `build/`, `src/test/` |
| `frontend/Dockerfile` | Node 20 `npm ci` + `ng build` → `nginx:1.27-alpine` |
| `frontend/nginx.conf` | SPA `try_files`; `/api/` and `/ws` → `backend:8081`; `/actuator` → 404 |
| `frontend/.dockerignore` | Excludes `node_modules/`, `dist/`, `.angular/` |
| `docker-compose.yml` | `postgres` + `backend` + `frontend` |
| `.env.example` | Local JDBC + `FRONTEND_PORT` |

### Compose wiring

- Backend env: `DATABASE_URL=jdbc:postgresql://postgres:5432/arbitrage` (container DNS, port **5432**)
- Backend health: `GET /actuator/health` (Dockerfile `HEALTHCHECK`)
- Frontend waits on `service_healthy` backend
- Host ports: Postgres **5437**, frontend **`${FRONTEND_PORT:-8080}`**; backend not published by default

### Nginx / Angular path

Angular application builder emits `dist/frontend/browser/` — that folder is copied to `/usr/share/nginx/html`. Relative frontend URLs (`/api/...`, `SockJS('/ws')`) work without environment files.

---

## Phase 2 — Hardening

### API errors

`GlobalExceptionHandler` returns a stable `{error, timestamp, path}` body:

| Status | Trigger |
|---|---|
| 404 | `NoHandlerFoundException` / `NoResourceFoundException` |
| 400 | `IllegalArgumentException`, missing/typed request params |
| 500 | Other exceptions — message is always `Internal server error`; stack logged |

### CORS / WebSocket

- New `CorsConfig` for `/api/**` with `http://localhost:*` and `http://127.0.0.1:*`
- Per-controller `@CrossOrigin(origins = "*")` removed from `SpreadController`, `OrderBookController`, `DatabaseController`
- SockJS allowed origin patterns unchanged (covers `:4200` hybrid and `:8080` Compose)

### Logging / actuator

`application.properties`:

- `logging.level.com.cryptoarbitrage.monitor=INFO`
- Hibernate SQL at `WARN`
- `management.endpoints.web.exposure.include=health`
- `management.endpoint.health.show-details=never`

---

## Verification (2026-08-08)

```bash
docker compose up --build
# On this machine port 8080 was occupied by another project:
FRONTEND_PORT=8888 docker compose up --build
```

Checks performed:

| Check | Result |
|---|---|
| `GET /` (SPA) | HTTP 200, `index.html` served |
| `GET /api/pairs` via Nginx | 50 tracked pairs |
| `GET /api/spreads/latest` | Includes `BTC/USD` and `ETH/USD` best rows |
| `GET /ws/info` (SockJS) | JSON info, `websocket: true` |
| Backend `/actuator/health` | `{"status":"UP"}` |
| Frontend container html tree | `index.html` + hashed JS/CSS present |

---

## Docs updated

- [README.md](../../README.md) — Compose quick start first
- [RUN.md](../RUN.md) — full stack + hybrid + troubleshooting
- [ARCHITECTURE.md](../ARCHITECTURE.md) — Dockerfiles, five venues, selective poll, full REST surface
- [SPRINT.md](./SPRINT.md) — Sprint 4 + DoD Compose items checked
- [IDEA.MD](../IDEA.MD) — marked historical; points to shipped docs
