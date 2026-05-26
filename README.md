# cloud-app

A Spring Boot / Kotlin app deployed to DigitalOcean Kubernetes for the UTM AC cloud lab.

`GET /` returns `Hello World! Visit #N` where N is a counter persisted in Postgres,
proving the database container's PVC really persists data across pod restarts.

## Two-step workflow

| Step | Folder | What |
|---|---|---|
| **1. Test locally first** | [`local/`](local/) | `docker compose up` — app + Postgres on your laptop, all demos work, no money spent |
| **2. Deploy to the cloud** | [`remote/`](remote/) | One-time DigitalOcean bootstrap, then CI auto-deploys on push to `main` |

## Documentation

- [`local/README.md`](local/README.md) — run + test locally with docker-compose
- [`remote/README.md`](remote/README.md) — DigitalOcean cluster bootstrap runbook (14 steps)
- [`docs/INFRASTRUCTURE.md`](docs/INFRASTRUCTURE.md) — runtime architecture, components, failure modes
- [`docs/superpowers/specs/2026-05-25-cloud-app-k8s-deploy-design.md`](docs/superpowers/specs/2026-05-25-cloud-app-k8s-deploy-design.md) — design rationale

## Running tests

No JDK 17 needed locally — gradle runs in Docker:

```bash
docker run --rm -v "$PWD:/app" -w /app gradle:8.7-jdk17 gradle test
```

On Windows git-bash use PowerShell to avoid path-mangling issues.

## Lab requirements coverage

| # | Requirement | Where |
|---|---|---|
| 1 | Docker image | [`Dockerfile`](Dockerfile) |
| 2 | Published to registry | [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) → DOCR |
| 3 | Deployed to Kubernetes | [`k8s/`](k8s/) |
| 4 | K8s on cloud provider | DOKS (per [`remote/README.md`](remote/README.md)) |
| 5 | Internet-accessible | ingress-nginx + DigitalOcean LoadBalancer |
| 6 | Manual scaling | `kubectl scale deploy/cloud-app --replicas=N` |
| 7 | Zero-downtime updates | RollingUpdate strategy + readiness probe in [`app-deployment.yaml`](k8s/base/app-deployment.yaml) |
| 8 | Rollback | `kubectl rollout undo deployment/cloud-app` |
| 9 | Monitoring | kube-prometheus-stack (Prometheus + Grafana + Alertmanager) |
| 10 | Autoscaling | [`k8s/base/app-hpa.yaml`](k8s/base/app-hpa.yaml) (CPU 70% / memory 80%, 2–5 replicas) |
| 11 | Centralized logs | loki-stack + Promtail, viewed in Grafana |
| 12 | Metrics emission | micrometer-registry-prometheus + [`ServiceMonitor`](k8s/base/app-servicemonitor.yaml) scraping `/actuator/prometheus` |
| 13 | DB in separate container | [`postgres-statefulset.yaml`](k8s/base/postgres-statefulset.yaml) |
| 14 | Storage mounted to DB | `volumeClaimTemplates` with `do-block-storage` 10 GiB |
