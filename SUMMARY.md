# Lab Summary — cloud-app on DigitalOcean Kubernetes

**Student:** Adrian Gherman
**Repository:** https://github.com/adrian28102001/cloud-app
**Public URL:** http://cloud-app.144.126.244.248.nip.io/

## What the app does

A Spring Boot 3.2 / Kotlin app. `GET /` writes a row into PostgreSQL and returns
`Hello World! Visit #N` where N is the persisted count — designed so the
database container's persistent storage is *meaningfully* exercised
(killing pods does not reset the count).

## Architecture in one paragraph

The image is built by a multi-stage Dockerfile and pushed to the
**DigitalOcean Container Registry** by **GitHub Actions** on every push to
`main`. It runs in a **DigitalOcean Kubernetes (DOKS)** cluster of 2 worker
nodes in region `fra1`, behind an **ingress-nginx** ingress controller that
provisions a public **DigitalOcean LoadBalancer**. PostgreSQL runs as a
separate `StatefulSet` pod with a 10 GiB `do-block-storage` PVC. The
**HorizontalPodAutoscaler** keeps replicas between 2 and 5 based on CPU.
Observability is provided by **kube-prometheus-stack** (Prometheus + Grafana
+ Alertmanager) and **loki-stack** (Loki + Promtail).

## Technology used at each layer

| Layer | Technology | Purpose |
|---|---|---|
| **Application code** | Spring Boot 3.2, Kotlin 1.9, JPA, Actuator, Micrometer Prometheus | Web app, DB access, health probes, metrics endpoint |
| **Local development** | docker-compose (Postgres + app) | Reproduce the full stack on a laptop |
| **Container image** | Multi-stage Dockerfile → `gcr.io/distroless/java17:nonroot` | ~200 MB image, no shell, runs as non-root |
| **Container registry** | DigitalOcean Container Registry (DOCR), free starter tier | Private registry for our image |
| **Kubernetes** | DOKS — 2 × `s-2vcpu-2gb` worker nodes in `fra1` | Managed K8s, no master to operate |
| **Manifest tooling** | Kustomize (`k8s/base` + `k8s/overlays/prod`) | Plain YAML, environment overlays |
| **Database persistence** | StatefulSet + `volumeClaimTemplates`, 10 GiB `do-block-storage` (CSI) | Stable identity + per-pod PVC; data survives pod loss |
| **Internet entry point** | ingress-nginx (Helm) → DigitalOcean LoadBalancer | Public IP 144.126.244.248, host via `nip.io` |
| **Update strategy** | Deployment `RollingUpdate`, `maxUnavailable: 0`, `maxSurge: 1`, readiness probe | Zero-downtime deploys |
| **Rollback** | `kubectl rollout undo`, `revisionHistoryLimit: 5` | Revert to previous ReplicaSet, also zero-downtime |
| **Autoscaling** | HPA v2 (CPU 70%, replicas 2–5) + metrics-server (Helm) | Scale on real CPU usage |
| **Monitoring** | kube-prometheus-stack (Helm) — Prometheus + Grafana + Alertmanager + node-exporter + kube-state-metrics | Cluster + app metrics, dashboards, alerts |
| **Metrics emission** | Spring Boot Actuator + `micrometer-registry-prometheus` exposed at `/actuator/prometheus`, scraped via a `ServiceMonitor` resource | App-level metrics into Prometheus |
| **Centralized logging** | loki-stack (Helm) — Loki + Promtail (DaemonSet) | All container logs, queried in Grafana |
| **Secret handling** | Kubernetes `Secret` for Postgres credentials, generated and applied out-of-band | Never committed to git |
| **CI/CD** | GitHub Actions workflow — Gradle test → Docker build → push to DOCR → `kubectl set image` → `kubectl rollout status` | Push to `main` deploys automatically |

## Lab requirements coverage

| # | Requirement | How it is satisfied |
|---|---|---|
| 1 | Run as a Docker image | Multi-stage `Dockerfile`, distroless runtime |
| 2 | Published to a Docker registry | DigitalOcean Container Registry, pushed by CI |
| 3 | Deployed to a Kubernetes cluster | Kustomize manifests applied to DOKS |
| 4 | K8s cluster on a cloud provider | DOKS managed Kubernetes |
| 5 | Accessible from the internet | ingress-nginx + DigitalOcean LoadBalancer |
| 6 | Can scale the application | `kubectl scale deploy/cloud-app --replicas=N` |
| 7 | Updates without downtime | RollingUpdate strategy + readiness probe |
| 8 | Rollback to previous version | `kubectl rollout undo deployment/cloud-app` |
| 9 | Monitoring | kube-prometheus-stack (Prometheus + Grafana) |
| 10 | Autoscale based on load | HorizontalPodAutoscaler on CPU 70%, replicas 2–5 |
| 12a | Centralized logs | loki-stack (Loki + Promtail), viewed in Grafana |
| 12b | Send metrics to monitoring system | Micrometer Prometheus + ServiceMonitor |
| 13 | Database in a separate container | PostgreSQL 16 in its own StatefulSet pod |
| 14 | Storage mounted to the DB container | `volumeClaimTemplates` → 10 GiB `do-block-storage` PVC |

> The original lab brief skips number 11 and uses number 12 twice
> (logs and metrics). Listed as 12a/12b above to disambiguate.

## End-to-end deploy flow (what we did)

1. Local docker-compose verified the app + Postgres counter and persistence behaviour.
2. `doctl registry create adrian-cloud-app` — registry created.
3. `doctl kubernetes cluster create adrian-doks ...` — DOKS cluster created (≈ 5 min).
4. `doctl kubernetes cluster registry add adrian-doks` — cluster authenticated to pull from DOCR.
5. `docker build` + `docker push` of the bootstrap image to DOCR.
6. `helm install ingress-nginx` — public LoadBalancer with IP `144.126.244.248`.
7. `helm install kube-prometheus-stack` and `helm install loki` in parallel.
8. `kubectl apply -k k8s/overlays/prod` — app + Postgres deployed.
9. `helm install metrics-server` — HPA can read CPU/memory.
10. GitHub Actions secret + variables configured via `gh` CLI.
11. CI verified end-to-end: push to `main` → tests run → image pushed → `kubectl set image` → rollout completes.

## Where things live on the cluster

```
namespace: cloud-app
  ├─ Deployment cloud-app (2 replicas, autoscaled to 2–5)
  ├─ Service cloud-app (ClusterIP)
  ├─ Ingress cloud-app (host cloud-app.144.126.244.248.nip.io)
  ├─ HorizontalPodAutoscaler cloud-app
  ├─ ServiceMonitor cloud-app (scraped by Prometheus)
  ├─ StatefulSet postgres (1 replica)
  ├─ Service postgres (headless)
  ├─ PersistentVolumeClaim data-postgres-0 (10 GiB do-block-storage)
  └─ Secret postgres-secret (credentials)

namespace: ingress-nginx
  └─ ingress-nginx-controller (Deployment + LoadBalancer Service)

namespace: monitoring
  ├─ kube-prometheus-stack (Prometheus, Grafana, Alertmanager, node-exporter, kube-state-metrics)
  └─ loki-stack (Loki, Promtail DaemonSet)

namespace: kube-system
  └─ metrics-server (Deployment, feeds the HPA)
```

## Demonstrations available

- The application responds with an incrementing counter at the public URL.
- Killing the application pod preserves the counter (state lives in Postgres).
- Killing the Postgres pod preserves the counter (PVC reattaches to the recreated pod).
- A rolling update from CI produces zero failed requests during the rollout.
- `kubectl rollout undo` reverts to the previous version, also without downtime.
- A 60-second load test causes the HPA to scale replicas up; idle time scales them back down.
- Prometheus dashboards in Grafana show CPU, memory, and HTTP request rate from `/actuator/prometheus`.
- Loki in Grafana shows live application logs filtered by namespace.

## Documentation in this repository

| File | Contents |
|---|---|
| `README.md` | Project overview, local + remote workflows |
| `REQUIREMENTS.md` | Per-requirement implementation with verification commands |
| `docs/INFRASTRUCTURE.md` | Runtime architecture diagram and component details |
| `docs/superpowers/specs/2026-05-25-cloud-app-k8s-deploy-design.md` | Original design specification |
| `local/README.md` | Local development with docker-compose |
| `remote/README.md` | DigitalOcean cluster bootstrap runbook |
