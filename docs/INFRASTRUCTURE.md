# Infrastructure & Architecture

This document describes the runtime architecture of `cloud-app` once deployed to DigitalOcean.

## High-level topology

```
                          Internet
                              │
                              ▼
                  ┌────────────────────────┐
                  │  DigitalOcean LB       │  $12/mo, public IP
                  │  (auto-provisioned by  │
                  │   ingress-nginx Svc)   │
                  └───────────┬────────────┘
                              │ :80
                              ▼
   ┌──────────────────────────────────────────────────────────┐
   │             DOKS cluster — `adrian-doks` (fra1)          │
   │             2 × s-2vcpu-2gb worker nodes                 │
   │                                                          │
   │  ┌────────────── namespace: ingress-nginx ────────────┐  │
   │  │  ingress-nginx-controller (Deployment, Svc=LB)     │  │
   │  └──────────────────────┬─────────────────────────────┘  │
   │                         │ Host: cloud-app.<lb>.nip.io    │
   │                         ▼                                │
   │  ┌──────────────── namespace: cloud-app ──────────────┐  │
   │  │                                                    │  │
   │  │  Ingress ──► Service `cloud-app` (ClusterIP :80)   │  │
   │  │                       │                            │  │
   │  │                       ▼                            │  │
   │  │  Deployment `cloud-app` (2–5 replicas, HPA)        │  │
   │  │   ├─ container: distroless java17 + boot jar       │  │
   │  │   ├─ probes: /actuator/health/{liveness,readiness} │  │
   │  │   └─ metrics: /actuator/prometheus :8080           │  │
   │  │                       │                            │  │
   │  │                       │ JDBC                       │  │
   │  │                       ▼                            │  │
   │  │  Service `postgres` (headless, :5432)              │  │
   │  │   └─► StatefulSet `postgres` (1 replica)           │  │
   │  │        └─ container: postgres:16-alpine            │  │
   │  │           └─ volumeMount /var/lib/postgresql/data  │  │
   │  │              └─ PVC `data-postgres-0`              │  │
   │  │                 └─ PV (do-block-storage, 10 GiB)   │  │
   │  └────────────────────────────────────────────────────┘  │
   │                                                          │
   │  ┌──────────── namespace: monitoring ──────────────────┐ │
   │  │  kube-prometheus-stack:                             │ │
   │  │    Prometheus  ◄── scrapes ServiceMonitor           │ │
   │  │    Grafana                                          │ │
   │  │    Alertmanager                                     │ │
   │  │    node-exporter (DaemonSet)                        │ │
   │  │    kube-state-metrics                               │ │
   │  │  loki-stack:                                        │ │
   │  │    Loki (PVC 10 GiB)                                │ │
   │  │    Promtail (DaemonSet, tails /var/log/pods)        │ │
   │  └─────────────────────────────────────────────────────┘ │
   └──────────────────────────────────────────────────────────┘
                              ▲
                              │ kubectl set image
                              │
                  ┌────────────────────────┐
                  │  GitHub Actions        │
                  │  on: push: [main]      │
                  │  → test → build →      │
                  │  → push to DOCR →      │
                  │  → roll out            │
                  └───────────┬────────────┘
                              │
                              ▼
                  ┌────────────────────────┐
                  │  DOCR registry         │  free tier, 500 MB
                  │  registry.digital      │
                  │  ocean.com/<name>/     │
                  │  cloud-app:<sha>       │
                  └────────────────────────┘
```

## Components

### Application

Spring Boot 3.2 / Kotlin 1.9 on JDK 17. One REST controller (`MainController`) at `GET /`. On every request it inserts a `Visit` row (timestamp) and returns `Hello World! Visit #N`. JPA-backed via `VisitRepository` extending `JpaRepository<Visit, Long>`.

Observability surface exposed via Spring Boot Actuator:
- `/actuator/health/liveness` — used by k8s livenessProbe
- `/actuator/health/readiness` — used by k8s readinessProbe
- `/actuator/prometheus` — Micrometer-Prometheus exposition format, scraped by Prometheus

### Container image

Multi-stage build (`Dockerfile`):
1. **build stage**: `gradle:8.7-jdk17` runs `gradle bootJar` (tests skipped here — CI runs them).
2. **runtime stage**: `gcr.io/distroless/java17-debian12:nonroot`, only the JRE + the boot jar.

Final image ~200 MB, runs as `nonroot` user, no shell, no package manager.

### Container registry

DigitalOcean Container Registry (`registry.digitalocean.com/<name>/cloud-app`). Free starter tier (500 MB). Tags: `:<git-sha>` (immutable, used by Deployments) and `:latest` (mutable, convenience).

Pull access for DOKS is granted by `doctl kubernetes cluster registry add`, which creates an image-pull `Secret` in every namespace and patches the default ServiceAccount to reference it. No explicit `imagePullSecrets:` in the Deployment manifest.

### Kubernetes manifests

All manifests are Kustomize-managed:

```
k8s/
├── base/
│   ├── kustomization.yaml          # lists 8 resources
│   ├── namespace.yaml              # creates `cloud-app` namespace
│   ├── postgres-service.yaml       # headless Service for the StatefulSet
│   ├── postgres-statefulset.yaml   # 1× postgres:16-alpine, 10 GiB PVC
│   ├── postgres-secret.example.yaml# placeholder (NOT in kustomization)
│   ├── app-deployment.yaml         # 2× cloud-app, rollingUpdate, probes
│   ├── app-service.yaml            # ClusterIP :80 → :8080
│   ├── app-ingress.yaml            # nip.io host → cloud-app Service
│   ├── app-hpa.yaml                # CPU 70% / mem 80%, 2–5 replicas
│   └── app-servicemonitor.yaml     # Prometheus discovery
└── overlays/prod/
    ├── kustomization.yaml          # patches image tag
    └── image-patch.yaml            # CI rewrites this on every deploy
```

### Update strategy

`Deployment.spec.strategy`:
- `type: RollingUpdate`
- `maxUnavailable: 0` — never drop below desired replica count
- `maxSurge: 1` — at most one extra pod above desired during a roll

Combined with `readinessProbe`, this gives true zero-downtime updates: a new pod must report healthy before any old pod is terminated, and the Service load-balances across only Ready pods throughout.

Rollback: `kubectl rollout undo deployment/cloud-app`. Deployment retains `revisionHistoryLimit: 5` past ReplicaSets.

### Autoscaling

`HorizontalPodAutoscaler` (`autoscaling/v2`) watches the `cloud-app` Deployment:
- `minReplicas: 2`, `maxReplicas: 5`
- Metrics: CPU utilization target 70%, memory utilization target 80%
- Uses `metrics-server` (pre-installed in DOKS) as the data source

### Database tier

`StatefulSet` named `postgres`, replicas: 1, `serviceName: postgres` (headless Service for stable pod DNS). Container is `postgres:16-alpine` with credentials read from the `postgres-secret` `Secret` via `envFrom`.

`volumeClaimTemplates` requests a 10 GiB PVC from `storageClassName: do-block-storage` (DigitalOcean's CSI block-storage driver). Mounted at `/var/lib/postgresql/data`, `subPath: pgdata` (alpine image expects an empty parent directory).

Liveness + readiness use `pg_isready -U $POSTGRES_USER -d $POSTGRES_DB`.

### Ingress / public exposure

`ingress-nginx` Helm chart in its own namespace. Creates a DigitalOcean LoadBalancer (one cluster-wide LB for all ingresses). Our `Ingress` resource binds host `cloud-app.<LB_IP>.nip.io` (nip.io is a wildcard public DNS — no domain purchase needed) to the `cloud-app` Service on port 80.

### Observability

**Metrics** — `kube-prometheus-stack` Helm chart in `monitoring`:
- Prometheus auto-discovers our app via the `ServiceMonitor` (labelled `release: kube-prometheus-stack` to match the chart's selector). Scrape interval 15s on `/actuator/prometheus`.
- Grafana ships pre-built dashboards for nodes, pods, kubelet, kube-state.
- Alertmanager included but no custom alerts configured.

**Logs** — `loki-stack` Helm chart in `monitoring`:
- Promtail runs as a DaemonSet, tailing `/var/log/pods/*/*.log` on every node.
- Loki stores log chunks in a 10 GiB PVC backed by `do-block-storage`.
- Same Grafana instance queries Loki — Explore → `{namespace="cloud-app"}` tails app logs.

No code changes in the app for logging: Spring Boot logs to stdout, kubelet captures, Promtail ships.

### CI/CD

`.github/workflows/deploy.yml`, triggered on push to `main` or manual dispatch:

1. Checkout
2. JDK 17 (Temurin) via `actions/setup-java@v4` with Gradle cache
3. `./gradlew test` — runs all 6 tests
4. Install `doctl`, log in to DOCR
5. `docker build` → push image tagged `:${{ github.sha }}` and `:latest`
6. `doctl kubernetes cluster kubeconfig save`
7. `kubectl set image deployment/cloud-app cloud-app=<image>`
8. `kubectl rollout status --timeout=3m` — fails the job if pods don't go Ready

Required GitHub config:
- Secret: `DIGITALOCEAN_ACCESS_TOKEN`
- Variables: `DOCR_NAME`, `DOKS_CLUSTER`

### Secret handling

`postgres-secret` is the only application secret. It is **not** committed — `k8s/base/postgres-secret.example.yaml` is a placeholder, explicitly excluded from `kustomization.yaml`. The real secret is generated and `kubectl apply`-ed out of band at bootstrap (see `docs/DEPLOY.md` step 6).

Production-grade secret management (Sealed Secrets, External Secrets Operator, Vault) is out of scope for the lab; the design doc lists this as a known trade-off.

## Capacity & cost (steady-state)

| Resource | Spec | Cost |
|---|---|---|
| DOKS control plane | managed | $0 |
| Worker nodes | 2 × s-2vcpu-2gb | $24/mo |
| LoadBalancer | 1× small | $12/mo |
| Block storage (Postgres + Loki) | 20 GiB total | $2/mo |
| DOCR | starter tier, 500 MB | $0 |
| **Total** | | **~$38/mo** |

Covered by the $200 / 60-day DigitalOcean new-user credit.

## Failure modes & recovery

| Failure | Recovery |
|---|---|
| App pod crash | Liveness probe fails → kubelet restarts container. Service stops routing to it via readiness during restart. |
| App node crash | K8s reschedules pods on the other node. HPA may add replicas. |
| Postgres pod crash | StatefulSet recreates `postgres-0`. PVC `data-postgres-0` re-attaches automatically — data survives. |
| Postgres node crash | StatefulSet schedules `postgres-0` on another node; DO block volume detaches and re-attaches. ~30–60s downtime for DB-bound requests. |
| Bad image deploy | Readiness fails on new pods → rolling update halts → `kubectl rollout undo`. Zero impact on traffic (old pods kept serving). |
| LB IP change | New ingress host needed (`cloud-app.<new-IP>.nip.io`). Avoid by reserving a Floating IP (DO feature, $0 while attached). |

## Out of scope

- TLS termination / Let's Encrypt (would add cert-manager and a real domain).
- HA Postgres (single replica; would use Patroni or CloudNativePG operator).
- Network policies / pod security standards enforcement.
- Postgres backups.
- Multi-region.
