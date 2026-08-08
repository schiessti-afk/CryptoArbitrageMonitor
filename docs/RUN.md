# Local run

## Full stack (recommended)

Starts PostgreSQL, the Spring Boot backend, and Nginx serving the production Angular build.

```bash
docker compose up --build
```

Open **http://localhost:8080**

Nginx proxies `/api` and SockJS `/ws` to the backend. Flyway applies migrations on backend startup.

### Custom frontend port

If host port 8080 is already taken, set `FRONTEND_PORT` (see `.env.example`):

```bash
# bash / macOS / Linux
FRONTEND_PORT=8888 docker compose up --build

# PowerShell
$env:FRONTEND_PORT='8888'; docker compose up --build
```

### Ports

| Service | Host | Notes |
|---|---|---|
| Frontend (Nginx) | **8080** (or `FRONTEND_PORT`) | Browser entry point |
| PostgreSQL | **5437** | Mapped for hybrid local-dev |
| Backend | — | Compose network only (`backend:8081`) |

### Stop

```bash
docker compose down
```

Add `-v` to also remove the Postgres volume.

---

## Hybrid local development

Use when iterating on backend or frontend without rebuilding images. Postgres still runs in Compose.

### 1. Postgres

```bash
docker compose up -d postgres
```

### 2. Backend

```bash
# macOS / Linux
cd backend && ./gradlew bootRun

# Windows (PowerShell)
cd backend
.\gradlew.bat bootRun
```

API: **http://localhost:8081**  
Datasource defaults match `.env.example` (`localhost:5437`).

### 3. Frontend (second terminal)

```bash
cd frontend
npm install          # first time only
npm start
```

Open **http://localhost:4200** — the Angular dev server proxies `/api` and `/ws` to `:8081`.

---

## Troubleshooting

| Symptom | What to check |
|---|---|
| Port 8080 in use | Set `FRONTEND_PORT` and reopen that URL |
| Backend never healthy | `docker compose logs backend`; confirm Postgres is healthy |
| Empty dashboard / not LIVE | Wait ~30s after backend is up; check exchange availability chips |
| Local `bootRun` cannot reach DB | Ensure `docker compose up -d postgres` and JDBC URL uses port **5437** |
| `java -version` shows 1.8 | Point `JAVA_HOME` at JDK 17+ (see README prerequisites) |

More detail: [README](../README.md), [Architecture](./ARCHITECTURE.md).
