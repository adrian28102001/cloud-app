# Cloud-App Kubernetes Deployment — Design Spec

**Status:** Approved
**Date:** 2026-05-25
**Author:** Adrian (with Claude)
**Lab:** UTM AC — Cloud deployment of a Spring Boot app

## 1. Goal

Take the existing `cloud-app` Spring Boot/Kotlin "Hello World" app and ship it to a publicly-reachable Kubernetes cluster on DigitalOcean, satisfying all 14 requirements from `README.md`:

containerized → registry → DOKS → internet-accessible → manual & auto scaling → rolling updates & rollback → centralized metrics & logs → Postgres in a separate pod with persistent storage.

## 2. Non-goals

- Multi-region / HA Postgres (single replica is enough for the lab).
- Production-grade secret management (we use plain `Secret` objects, not Vault/Sealed-Secrets).
- TLS via Let's Encrypt (optional stretch; not required by the lab and adds DNS dependency).
- Authentication / authorization on the app endpoints.

## 3. Application changes

### 3.1 Functional behavior

`GET /` now:

1. Inserts a new row into `visits` table (or atomically increments a counter row).
2. Returns `"Hello World! Visit #N"` where N is the current count.

This proves the database connection works and that the PVC actually persists data across pod restarts (kill the pod → count keeps going up).

### 3.2 Dependencies added to `build.gradle.kts`

- `org.springframework.boot:spring-boot-starter-data-jpa`
- `org.postgresql:postgresql` (runtime)
- `org.springframework.boot:spring-boot-starter-actuator`
- `io.micrometer:micrometer-registry-prometheus` (runtime)

### 3.3 Code additions

- `entity/Visit.kt` — `@Entity` with `id: Long` (auto), `at: Instant`.
- `repo/VisitRepository.kt` — `JpaRepository<Visit, Long>` with `count()`.
- `MainController.kt` — inject repo, save a row each call, return text with `repo.count()`.

### 3.4 Configuration

- `application.properties` — defaults for local dev (in-memory H2 disabled; expects Postgres).
- `application-prod.properties` — production profile, all values from env vars:
  - `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/appdb`
  - `SPRING_DATASOURCE_USERNAME=${POSTGRES_USER}`
  - `SPRING_DATASOURCE_PASSWORD=${POSTGRES_PASSWORD}`
  - `SPRING_JPA_HIBERNATE_DDL_AUTO=update`
  - `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,prometheus`
  - `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true`
  - `MANAGEMENT_HEALTH_LIVENESSSTATE_ENABLED=true`
  - `MANAGEMENT_HEALTH_READINESSSTATE_ENABLED=true`

Endpoints exposed:
- `/` — counter
- `/actuator/health/liveness` — used by k8s liveness probe
- `/actuator/health/readiness` — used by k8s readiness probe
- `/actuator/prometheus` — scraped by Prometheus

## 4. Containerization

### 4.1 Dockerfile (multi-stage)

```
FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY --chown=gradle:gradle . .
RUN gradle bootJar --no-daemon

FROM gcr.io/distroless/java17-debian12:nonroot
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
USER nonroot
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

### 4.2 .dockerignore

Excludes `.git`, `build/`, `.gradle/`, `.idea/`, local IDE files, docs.

### 4.3 Registry

DigitalOcean Container Registry (DOCR), free tier (500 MB). Image tagged
`registry.digitalocean.com/<reg-name>/cloud-app:<git-sha>` and `:latest`.

DOKS gets pull access via `doctl kubernetes cluster registry add` (one command, no manual imagePullSecrets juggling).

## 5. Kubernetes layout

### 5.1 Cluster

- **Provider:** DigitalOcean Kubernetes (DOKS)
- **Version:** latest stable (1.30+)
- **Region:** `fra1` (closest to MD) — *user can change*
- **Node pool:** 2 × `s-2vcpu-2gb` (~$24/mo) — minimum for HPA scale-out demo
- **LoadBalancer:** 1 × small DO LB (~$12/mo) created automatically by ingress-nginx

### 5.2 Namespaces

- `cloud-app` — the app + Postgres
- `monitoring` — kube-prometheus-stack + loki-stack
- `ingress-nginx` — ingress controller

### 5.3 Manifest layout (Kustomize)

```
k8s/
├── base/
│   ├── kustomization.yaml
│   ├── namespace.yaml
│   ├── app-deployment.yaml
│   ├── app-service.yaml
│   ├── app-ingress.yaml
│   ├── app-hpa.yaml
│   ├── app-servicemonitor.yaml
│   ├── postgres-statefulset.yaml
│   ├── postgres-service.yaml
│   └── postgres-secret.example.yaml   (real secret applied out-of-band)
└── overlays/
    └── prod/
        ├── kustomization.yaml
        └── image-patch.yaml            (sets image tag — CI writes to this)
```

### 5.4 App Deployment — key bits

- `replicas: 2` (HPA will override)
- `strategy: RollingUpdate`, `maxUnavailable: 0`, `maxSurge: 1` → zero downtime (req 7)
- Liveness probe: `GET /actuator/health/liveness`, initial 30s
- Readiness probe: `GET /actuator/health/readiness`, initial 10s
- Resources: `requests: 200m/256Mi`, `limits: 500m/512Mi`
- Env vars from `postgres-secret` for DB credentials
- `SPRING_PROFILES_ACTIVE=prod`
- Annotations for Prometheus scrape (also covered by ServiceMonitor)

### 5.5 HPA (req 10)

- `min: 2`, `max: 5`
- CPU target 70%, memory target 80%
- Requires metrics-server (DOKS ships with it)

### 5.6 Ingress

- ingress-nginx (Helm chart, default values)
- `Ingress` resource routing `/` to the app service
- Host: the LB's external IP via `nip.io` (e.g. `<lb-ip>.nip.io`) so we don't need a domain

### 5.7 Postgres StatefulSet

- `image: postgres:16-alpine`
- 1 replica, headless service `postgres` on port 5432
- `volumeClaimTemplates`: 10 GiB, storageClass `do-block-storage` (req 14)
- Resources: `requests: 250m/256Mi`, `limits: 1/1Gi`
- Credentials from `postgres-secret` (POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_DB)
- Readiness probe: `pg_isready`

### 5.8 Secret

`postgres-secret` containing `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`. **Real values applied manually**, never committed. `postgres-secret.example.yaml` shows the structure with placeholder values.

## 6. Observability

### 6.1 Metrics + monitoring (reqs 9, 12)

- `kube-prometheus-stack` Helm chart in `monitoring` namespace.
  - Prometheus scrapes the app via a `ServiceMonitor` matching `app=cloud-app`.
  - Grafana exposed via Ingress at `/grafana` (or separate Ingress host).
  - Default dashboards for nodes, pods, kubelet; we add a custom JSON dashboard with: requests/sec, p95 latency, JVM heap, DB pool gauges.
  - Alertmanager included but no custom alerts configured (default ones are enough for the lab).

### 6.2 Logging (req 11)

- `loki-stack` Helm chart in `monitoring`:
  - Loki: log storage (filesystem PVC, small).
  - Promtail: DaemonSet tailing all container logs and shipping to Loki.
  - Grafana Loki datasource auto-provisioned.

Demo: in Grafana → Explore → Loki → `{namespace="cloud-app"}` → see app logs in real time, including a log line per visit.

## 7. CI/CD

`.github/workflows/deploy.yml`, triggered on push to `main`:

1. Checkout
2. Set up JDK 17
3. `./gradlew bootJar` (with Gradle cache)
4. Install `doctl`, log in to DOCR using `DIGITALOCEAN_ACCESS_TOKEN` secret
5. `docker build -t registry.digitalocean.com/<reg>/cloud-app:${{ github.sha }} .`
6. `docker push`
7. `doctl kubernetes cluster kubeconfig save <cluster>`
8. `kubectl -n cloud-app set image deployment/cloud-app cloud-app=registry.digitalocean.com/<reg>/cloud-app:${{ github.sha }}`
9. `kubectl -n cloud-app rollout status deployment/cloud-app --timeout=3m`

Rollback (req 8) is `kubectl -n cloud-app rollout undo deployment/cloud-app`.

GitHub repository secret needed: `DIGITALOCEAN_ACCESS_TOKEN` with read/write registry + cluster admin scopes.

## 8. Mapping each lab requirement to its solution

| # | Requirement | Where solved |
|---|---|---|
| 1 | Run as Docker image | §4.1 Dockerfile |
| 2 | Published to a registry | §4.3 DOCR via CI |
| 3 | Deployed to K8s | §5 manifests |
| 4 | K8s on a cloud provider | §5.1 DOKS |
| 5 | Internet-accessible | §5.6 ingress-nginx + DO LB |
| 6 | Can scale the app | §5.4 `replicas`, manual `kubectl scale` |
| 7 | Update without downtime | §5.4 RollingUpdate strategy + readiness probe |
| 8 | Rollback to previous version | `kubectl rollout undo` (§7) |
| 9 | Monitor the application | §6.1 Prometheus + Grafana |
| 10 | Autoscale based on load | §5.5 HPA |
| 11 | Centralized logs | §6.2 Loki + Promtail |
| 12 | Send metrics to monitoring system | §3.2 micrometer-prometheus + §6.1 ServiceMonitor |
| 13 | Database in separate container | §5.7 Postgres StatefulSet |
| 14 | Storage mounted to DB container | §5.7 `volumeClaimTemplates` + `do-block-storage` |

## 9. Demo checklist (for instructor)

1. `kubectl get nodes,pods,svc,ingress,hpa,pvc -A` — show everything green.
2. `curl http://<lb-ip>.nip.io/` repeatedly — counter increments.
3. `kubectl delete pod -l app=cloud-app` — counter keeps going (proves DB+PVC).
4. `kubectl delete pod -l app=postgres` — pod restarts, data still there.
5. Push a trivial code change → watch GH Action → `kubectl rollout status` → curl during the update, observe zero downtime.
6. `kubectl rollout undo` → previous SHA back, no downtime.
7. Hammer the app with `hey` or `ab` → watch HPA scale up.
8. Open Grafana → dashboard shows traffic spike; Explore→Loki shows logs.

## 10. Cost & cleanup

- Cluster: 2 × s-2vcpu-2gb = $24/mo
- LoadBalancer: $12/mo
- Block storage: 10 GiB = $1/mo
- DOCR free tier: $0
- **Total: ~$37/mo** — covered by the $200 new-user credit.

Cleanup script (DOWN.sh) tears down everything via `doctl` to avoid surprise bills.

## 11. Risks & open questions

- **DigitalOcean account / billing:** user must create the account and a $200-credit PAT. We'll pause before any `doctl` call that creates paid resources.
- **App's `ddl-auto=update`** is fine for the lab but not production-clean. Acceptable trade-off.
- **Single Postgres pod** is a SPOF. Lab requirement doesn't require HA, and a real HA Postgres setup is a multi-day project on its own (operators, replication, failover). Out of scope.
- **No TLS** — endpoints are HTTP. Adding cert-manager + Let's Encrypt is a nice stretch goal documented in DEPLOY.md but not implemented by default.
