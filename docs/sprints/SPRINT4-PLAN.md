# Sprint 4 Plan — Ship V1

**Goal:** Production-like local deploy via Docker Compose, hardening (logging / API errors / CORS),
and documentation so a clean checkout reaches a live dashboard with one command.

**Exit criteria (from [SPRINT.md](./SPRINT.md)):** A new developer can clone, run
`docker compose up --build`, and see live indicative opportunities for `BTC/USD` and `ETH/USD`
(and the default USDT majors).

**Already delivered ahead of this sprint** (documented in Architecture; not re-implemented here):

- Env-based DB config (`DATABASE_*`)
- Externalized exchange URLs / timeouts / fees / poll / backoff props
- Exchange API limit summary
- `429` / timeout backoff (`ExchangeBackoffFilter` + `ExchangeBackoffStore`)
- MIT `LICENSE`
- Flash-on-change UI (Sprint 3)

---

## Decisions taken before planning

| Decision | Choice | Consequence |
|---|---|---|
| Compose entry point | Single browser URL via Nginx | Frontend publishes **`${FRONTEND_PORT:-8080}→80`**; backend stays on the Compose network (optional debug publish of 8081) |
| API / WebSocket in production | Nginx reverse-proxy same origin | Relative `/api` and `/ws` keep working; no frontend env file needed |
| Angular output | Application builder → `dist/frontend/browser` | Nginx `root` points at that folder |
| CORS | Central `CorsConfig` + SockJS origin patterns for local + Compose | Controllers drop per-class `@CrossOrigin`; Docker same-origin path does not need CORS |
| Error bodies | Stable JSON shape `{error,timestamp,path}` | `IllegalArgumentException` → 400; unknown → 500 with generic message (stack in logs only) |
| Health | Actuator `/actuator/health` | Backend Compose healthcheck; frontend waits on backend healthy |
| Postgres port | Keep host **5437** | Local `bootRun` against Compose Postgres unchanged |

---

## Phase 1 — Full-stack Docker Compose

**Deliverable:** `docker compose up --build` starts postgres + backend + Nginx-served Angular.

### 1.1 Backend image

`backend/Dockerfile` (multi-stage):

1. **Build:** JDK 17 + Gradle wrapper → `bootJar`
2. **Runtime:** JRE 17, run the fat JAR on port **8081**

`.dockerignore` excludes `.gradle/`, `build/`, tests artifacts noise.

Compose env for the backend container:

| Variable | Value inside Compose |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://postgres:5432/arbitrage` |
| `DATABASE_USERNAME` | `arbitrage` |
| `DATABASE_PASSWORD` | `arbitrage` |

Note the hostname is the Compose service name `postgres` and the **container** port `5432`, not host `5437`.

### 1.2 Frontend image + Nginx

`frontend/Dockerfile` (multi-stage):

1. **Build:** Node 20 → `npm ci` → `ng build` (production)
2. **Runtime:** `nginx:alpine` serving `dist/frontend/browser`

`frontend/nginx.conf`:

- `try_files` SPA fallback to `index.html`
- `location /api/` → `http://backend:8081`
- `location /ws` → `http://backend:8081` with WebSocket upgrade headers (SockJS)
- Sensible proxy timeouts for long-lived SockJS

### 1.3 Compose services

Extend root `docker-compose.yml`:

| Service | Role |
|---|---|
| `postgres` | Unchanged (healthcheck already present) |
| `backend` | Build `./backend`, wait for postgres healthy, expose health via actuator |
| `frontend` | Build `./frontend`, publish `${FRONTEND_PORT:-8080}:80`, wait for backend healthy |

Update `.env.example` with Compose-oriented comments (local vs in-compose JDBC URL).

### 1.4 Verify from clean-ish state

```bash
docker compose down -v   # optional: wipe DB volume once
docker compose up --build
# open http://localhost:8080
# expect LIVE (or recovering) and BTC/ETH opportunities within ~30s of healthy backend
```

---

## Phase 2 — Hardening (logging, API errors, CORS)

**Deliverable:** Predictable API error JSON, quieter/ clearer logs, CORS that covers local + Compose without wildcard sprawl on every controller.

### 2.1 Global API errors

Extend [`GlobalExceptionHandler`](../../backend/src/main/java/com/cryptoarbitrage/monitor/controller/GlobalExceptionHandler.java):

| Exception | Status | `error` field |
|---|---|---|
| `NoHandlerFoundException` / `NoResourceFoundException` | 404 | `Not found` |
| `IllegalArgumentException` | 400 | exception message |
| `MissingServletRequestParameterException` | 400 | clear parameter message |
| `MethodArgumentTypeMismatchException` | 400 | clear type message |
| Other `Exception` | 500 | `Internal server error` (log full stack; do not echo `ex.getMessage()`) |

Keep the existing `{error, timestamp, path}` shape used by REST clients.

### 2.2 Logging

In `application.properties`:

- Root / app package at `INFO`
- Poll / backoff / adapter failures stay visible at INFO/WARN (no DEBUG spam by default)
- Optional: slightly quieter Hibernate/Flyway if noisy at INFO

### 2.3 CORS + WebSocket origins

- Add `CorsConfig` registering `/api/**` for `http://localhost:*` and `http://127.0.0.1:*`
- Align `WebSocketConfig` SockJS `setAllowedOriginPatterns` with the same local patterns (Compose traffic is same-origin through Nginx, so SockJS origin is `http://localhost:8080`)
- Remove redundant `@CrossOrigin(origins = "*")` from controllers once the global config is in place

### 2.4 Actuator health for Compose

Expose only `health` (no broad actuator surface):

```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=never
```

---

## Phase 3 — Documentation and DoD

**Deliverable:** README / RUN / Architecture / Sprint checklist match reality; V1 DoD box for Compose checked.

### 3.1 Docs to update

| Doc | Change |
|---|---|
| [README.md](../../README.md) | Quick start: `docker compose up --build` → http://localhost:8080; keep local-dev section |
| [RUN.md](../RUN.md) | Compose full stack + hybrid local-dev |
| [ARCHITECTURE.md](../ARCHITECTURE.md) | Repo layout (Dockerfiles/nginx), topology note as shipped |
| [SPRINT.md](./SPRINT.md) | Tick Sprint 4 deliverables + DoD Compose item; link this plan |
| This sprint | Add [SPRINT4-IMPLEMENTATION.md](./SPRINT4-IMPLEMENTATION.md) after verification |

### 3.2 Definition of Done gaps closed by this sprint

- [x] Entire stack starts with `docker compose up --build`
- [x] README quick start verified from clean checkout
- [x] Final pass on logging and API error responses
- [x] Frontend Dockerfile + Nginx SPA/API/WS routing
- [x] Compose includes postgres, backend, frontend

---

## Explicitly out of scope

Cloud deployment, TLS termination, secrets managers, CI publish of images, Kubernetes, Redis, trade execution, private API keys — unchanged from [SPRINT.md](./SPRINT.md#explicitly-out-of-sprint-scope).

---

## Implementation order

1. Write this plan (done when committed to `docs/`)
2. Backend Dockerfile + `.dockerignore` + actuator health props
3. Frontend Dockerfile + `nginx.conf` + `.dockerignore`
4. Wire `docker-compose.yml` + `.env.example`
5. Hardening: exception handler, logging, CORS, WebSocket origins
6. `docker compose up --build` smoke test
7. Documentation + Sprint 4 checklist + implementation notes
