# Local — run and test the app on your machine

Everything in this folder runs on your laptop. No DigitalOcean, no cluster, no money. Goal: prove the app + Postgres work end-to-end before you spend any cloud credit.

## Prerequisites

- **Docker Desktop** (or Docker Engine ≥ 20) — must be running.
- That's it. No JDK 17 install needed — gradle runs inside Docker too.

Check:
```bash
docker --version
docker compose version
```

## Quick start (60 seconds)

From this `local/` directory:

```bash
docker compose up -d --build
```

Wait ~10 s for the app to be ready, then:

```bash
curl localhost:8080
# → Hello World! Visit #1
curl localhost:8080
# → Hello World! Visit #2
```

That's it — the app is running locally, talking to a real Postgres in a sibling container.

## What it spins up

| Container | Image | Host port | Purpose |
|---|---|---|---|
| `cloud-app` | built from `../Dockerfile` (distroless java17) | **8080** | The Spring Boot app |
| `cloud-app-pg` | `postgres:16-alpine` | **15432** | Postgres (15432 to avoid clashing with other local Postgres) |

Internally they talk over the compose network using DNS name `postgres:5432`. The 15432 mapping is only for you to connect with a SQL client from the host — the app itself doesn't use it.

## Useful commands

```bash
# Status
docker compose ps

# App logs (follow)
docker compose logs -f app

# DB logs
docker compose logs -f postgres

# Connect to the DB with psql (requires psql installed on host, OR exec into the pg container):
docker compose exec postgres psql -U appuser -d appdb -c "SELECT count(*) FROM visit;"

# Rebuild after a code change
docker compose up -d --build

# Stop containers, KEEP volume (DB data survives)
docker compose down

# Stop AND wipe the DB volume (fresh start)
docker compose down -v
```

## What to verify

These are the same demos you'll do later on the real cluster — proving them locally first means the cluster work is just plumbing.

### 1. Counter increments

```bash
for i in 1 2 3 4 5; do curl localhost:8080; echo; done
```
Each line should show `Hello World! Visit #N` with `N` going up.

### 2. Counter survives APP restart (DB holds the state)

```bash
curl localhost:8080                       # note the number
docker compose restart app
sleep 10
curl localhost:8080                       # number kept going
```

### 3. Counter survives DB restart (volume holds the state — local equivalent of a PVC)

```bash
curl localhost:8080
docker compose restart postgres
sleep 10
docker compose restart app                # app reconnects
sleep 10
curl localhost:8080                       # number kept going
```

### 4. Actuator endpoints (used as k8s liveness/readiness probes)

```bash
curl localhost:8080/actuator/health/liveness
# → {"status":"UP"}
curl localhost:8080/actuator/health/readiness
# → {"status":"UP"}
```

### 5. Prometheus metrics endpoint (what the ServiceMonitor scrapes in prod)

```bash
curl -s localhost:8080/actuator/prometheus | head -20
# → many lines of "# HELP ... # TYPE ... <metric> <value>"
```

## Run the test suite

```bash
# From the project root (one level up from this folder):
cd ..
docker run --rm -v "$PWD:/app" -w /app gradle:8.7-jdk17 gradle test
```

On Windows git-bash, paths get mangled — use PowerShell instead, or set `MSYS_NO_PATHCONV=1`.

Expected: `BUILD SUCCESSFUL`, 6 tests pass.

## Troubleshooting

**"port is already allocated"** — something else is using host port 8080 or 15432. Stop the conflicting container (`docker ps`) or change the port mapping in `docker-compose.yml`.

**App stuck in `Created` / never starts** — DB isn't healthy. Check `docker compose logs postgres`. If you changed credentials, you may need `docker compose down -v` to reset the volume (Postgres only initializes credentials on first start).

**`curl: (52) Empty reply from server`** — app is still starting. Wait 5–10 more seconds, or check `docker compose logs app` for the "Started CloudAppApplicationKt in N seconds" line.

**Counter reset to 1 unexpectedly** — you probably ran `docker compose down -v` which wipes the volume. Use plain `docker compose down` to preserve data between sessions.

**Build very slow** — first build downloads JDK 17 + all gradle dependencies (~250 MB). Subsequent builds use Docker layer cache and are much faster.

## When you're done locally → deploy to DigitalOcean

Once everything in this folder works, head to [`../remote/README.md`](../remote/README.md) for the cloud deployment runbook.
