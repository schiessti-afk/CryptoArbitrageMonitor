# Local run

## Backend

```bash
docker compose up -d postgres
cd backend && ./gradlew bootRun &
```

Windows (PowerShell):

```powershell
docker compose up -d postgres
cd backend
.\gradlew.bat bootRun
```

## Frontend (new terminal)

```bash
cd frontend
npm start
```

## Open

http://localhost:4200

Backend API: http://localhost:8080
