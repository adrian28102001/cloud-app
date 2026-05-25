# cloud-app

A Spring Boot / Kotlin app deployed to DigitalOcean Kubernetes for the UTM AC cloud lab.

`GET /` returns `Hello World! Visit #N` where N is a counter persisted in Postgres,
proving the database container's PVC really persists data across pod restarts.

## Local development

Postgres in Docker:
```bash
docker run -d --name pg -p 5432:5432 \
  -e POSTGRES_USER=appuser -e POSTGRES_PASSWORD=apppass -e POSTGRES_DB=appdb \
  postgres:16-alpine
```

The app (requires JDK 17 — or use Docker, see below):
```bash
./gradlew bootRun
curl localhost:8080
```

### No JDK 17 locally? Use Docker

```bash
docker run --rm -v "$PWD:/app" -w /app gradle:8.7-jdk17 gradle test
docker build -t cloud-app:dev .
```

## Tests

```bash
./gradlew test
# or, without local JDK 17:
docker run --rm -v "$PWD:/app" -w /app gradle:8.7-jdk17 gradle test
```

## Deploying to DigitalOcean

See [`docs/DEPLOY.md`](docs/DEPLOY.md) for the full bootstrap runbook.
See [`docs/superpowers/specs/2026-05-25-cloud-app-k8s-deploy-design.md`](docs/superpowers/specs/2026-05-25-cloud-app-k8s-deploy-design.md) for the design rationale and how each lab requirement is satisfied.

## Lab requirements coverage

| # | Requirement | Where |
|---|---|---|
| 1 | Docker image | [`Dockerfile`](Dockerfile) |
| 2 | Published to registry | [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) → DOCR |
| 3 | Deployed to Kubernetes | [`k8s/`](k8s/) |
| 4 | K8s on cloud provider | DOKS (per [`docs/DEPLOY.md`](docs/DEPLOY.md)) |
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
