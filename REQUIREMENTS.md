# Lab requirements — how each one is met

This document is the index between the 14 requirements stated in the original `README.md` and the concrete artifacts + commands that satisfy them.

> The original lab list has a numbering typo — two `12`s and no `11`. I'm preserving the original line numbers so the teacher can match them 1:1, and tagging the duplicated `12` as `12a` (logs) / `12b` (metrics) for clarity.

For each requirement:
- **What it asks for** — verbatim from the lab brief.
- **How we satisfy it** — the design choice, in one sentence.
- **Where to look** — files / lines in this repo.
- **How to verify** — the exact command, locally and/or on the cluster.

---

## 1. Run as a Docker image

> *"This project should be made to run as a Docker image."*

**How:** Multi-stage `Dockerfile`. Stage 1 (`gradle:8.7-jdk17`) builds the Spring Boot fat-jar. Stage 2 (`gcr.io/distroless/java17-debian12:nonroot`) is the runtime — only the JRE and our jar, no shell, no package manager, runs as `nonroot`. Final image ~200 MB.

**Where:** [`Dockerfile`](Dockerfile), [`.dockerignore`](.dockerignore)

**Verify locally:**
```bash
docker build -t cloud-app:dev .
docker run --rm -p 8080:8080 cloud-app:dev   # (needs a Postgres on the network; see local/)
docker image ls cloud-app:dev                 # ~200 MB
```

**Verify on cluster:** `kubectl -n cloud-app describe pod -l app=cloud-app | grep Image:`

---

## 2. Published to a Docker registry

> *"Docker image should be published to a Docker registry."*

**How:** DigitalOcean Container Registry (DOCR) on the free `starter` tier (500 MB). Every push to `main` builds the image, tags it `:<git-sha>` and `:latest`, and pushes to `registry.digitalocean.com/<reg-name>/cloud-app`.

**Where:** [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) — `Build and push image` step.

**Verify:** `doctl registry repository list-tags adrian-cloud-app/cloud-app` (after first push).

---

## 3. Deployed to a Kubernetes cluster

> *"Docker image should be deployed to a Kubernetes cluster."*

**How:** All Kubernetes resources are written as plain YAML under [`k8s/base/`](k8s/base/) and composed via Kustomize ([`k8s/overlays/prod/`](k8s/overlays/prod/)). 8 resources total: namespace, app Deployment + Service + Ingress + HPA + ServiceMonitor, Postgres StatefulSet + Service.

**Where:** [`k8s/`](k8s/) tree.

**Verify locally** (no cluster needed — renders the merged YAML):
```bash
docker run --rm -v "$PWD:/app" -w /app bitnami/kubectl:latest kustomize k8s/overlays/prod
```
Expect 8 resources rendered cleanly.

**Verify on cluster:** `kubectl -n cloud-app get all`

---

## 4. Kubernetes cluster running on a cloud provider

> *"Kubernetes cluster should be running on a cloud provider."*

**How:** DigitalOcean Kubernetes Service (DOKS) — managed control plane (free), 2 × `s-2vcpu-2gb` worker nodes in region `fra1` (closest to Moldova).

**Where:** [`remote/README.md`](remote/README.md) step 2 — the `doctl kubernetes cluster create` command.

**Verify:** `doctl kubernetes cluster list` and `kubectl get nodes` — should show 2 nodes both `Ready`.

---

## 5. Accessible from the internet

> *"Kubernetes cluster should be accessible from the internet."*

**How:** `ingress-nginx` Helm chart provisions a DigitalOcean LoadBalancer with a public IP. Our `Ingress` resource routes Host `cloud-app.<LB_IP>.nip.io` → `cloud-app` Service. (`nip.io` is a free public wildcard DNS so we don't need a custom domain.)

**Where:** [`k8s/base/app-ingress.yaml`](k8s/base/app-ingress.yaml); [`remote/README.md`](remote/README.md) step 7.

**Verify:**
```bash
LB_IP=$(kubectl -n ingress-nginx get svc ingress-nginx-controller \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
curl -H "Host: cloud-app.${LB_IP}.nip.io" http://${LB_IP}/
# → Hello World! Visit #N
```

---

## 6. Able to scale the application

> *"Kubernetes cluster should be able to scale the application."*

**How:** The Deployment exposes `spec.replicas` — `kubectl scale` changes it on the fly. No restart needed; ReplicaSet brings new pods up behind the Service.

**Where:** [`k8s/base/app-deployment.yaml`](k8s/base/app-deployment.yaml) (`replicas: 2` baseline).

**Verify:**
```bash
kubectl -n cloud-app scale deploy/cloud-app --replicas=4
kubectl -n cloud-app get pods -l app=cloud-app -w
# Expect 4 pods Running within ~10 s.
kubectl -n cloud-app scale deploy/cloud-app --replicas=2
```

---

## 7. Update without downtime

> *"Kubernetes cluster should be able to update the application without downtime."*

**How:** Three things together:
1. **Strategy** — `strategy: RollingUpdate` with `maxUnavailable: 0` and `maxSurge: 1`. Never below desired count; one new pod surges in.
2. **Readiness probe** — `/actuator/health/readiness`; the Service only routes to pods that have returned 200.
3. **Liveness probe** — `/actuator/health/liveness`; lets kubelet detect hung pods independently.

**Where:** [`k8s/base/app-deployment.yaml`](k8s/base/app-deployment.yaml) — `strategy:` block and `readinessProbe:` / `livenessProbe:`.

**Verify:** during a rolling update (CI push or manual `kubectl set image`), run a loop:
```bash
while true; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "Host: cloud-app.${LB_IP}.nip.io" http://${LB_IP}/
  sleep 0.2
done
# Expect: continuous 200s, zero 5xx.
```

---

## 8. Rollback to a previous version

> *"Kubernetes cluster should be able to rollback the application to a previous version."*

**How:** Built-in `kubectl rollout undo`. The Deployment keeps `revisionHistoryLimit: 5`, so the last 5 ReplicaSets are available to roll back to. Rollback uses the same RollingUpdate machinery → also zero-downtime.

**Where:** [`k8s/base/app-deployment.yaml`](k8s/base/app-deployment.yaml) — `revisionHistoryLimit: 5`.

**Verify:**
```bash
kubectl -n cloud-app rollout history deployment/cloud-app
kubectl -n cloud-app rollout undo deployment/cloud-app
kubectl -n cloud-app rollout status deployment/cloud-app
```

---

## 9. Monitor the application

> *"Kubernetes cluster should be able to monitor the application."*

**How:** `kube-prometheus-stack` Helm chart installed into the `monitoring` namespace. Bundles **Prometheus** (TSDB + scraper), **Grafana** (dashboards), **Alertmanager** (alert routing), **node-exporter** (host metrics), and **kube-state-metrics** (cluster object metrics). Ships with built-in dashboards for nodes, pods, kubelet.

**Where:** [`remote/README.md`](remote/README.md) step 8.

**Verify:**
```bash
kubectl -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80
# Open http://localhost:3000 (admin/admin)
# Dashboards → Kubernetes / Compute Resources / Namespace (Pods)
# Filter namespace=cloud-app → see CPU/mem of our app
```

---

## 10. Autoscale based on load

> *"Kubernetes cluster should be able to autoscale the application based on the load."*

**How:** `HorizontalPodAutoscaler` v2: targets `Deployment/cloud-app`, scales 2–5 replicas based on CPU utilization > 70% OR memory utilization > 80%. Driven by `metrics-server`, which ships with DOKS.

**Where:** [`k8s/base/app-hpa.yaml`](k8s/base/app-hpa.yaml).

**Verify:**
```bash
# Generate load (install hey: `choco install hey` or `brew install hey`)
hey -z 60s -c 50 -host "cloud-app.${LB_IP}.nip.io" http://${LB_IP}/
# In another terminal:
kubectl -n cloud-app get hpa cloud-app -w
# Expect: CPU% climbs, REPLICAS scales 2 → 3 → 4 → 5.
```

---

## 12a. Centralised logging (`Loki`, Kibana, etc.)

> *"Application logs should be stored in a centralised logging system (Loki, Kibana, etc.)"*
> *(original lab numbered this 12 — see note at the top of this file.)*

**How:** `loki-stack` Helm chart in `monitoring`. **Promtail** runs as a DaemonSet (one pod per node), tails `/var/log/pods/*/*.log`, and ships every line to **Loki**, which stores it on a 10 GiB PVC backed by `do-block-storage`. Loki is queried through the same Grafana from req 9.

**Where:** [`remote/README.md`](remote/README.md) step 9. App code: no changes — Spring Boot writes to stdout, that's all.

**Verify:**
```bash
# In Grafana → Explore → data source: Loki
# Query: {namespace="cloud-app"}
# → tails all app + postgres logs in real time. Hit the app and watch lines appear.
```

---

## 12b. Send metrics to a monitoring system

> *"Application should be able to send metrics to a monitoring system."*
> *(also numbered 12 in the original lab — see note above.)*

**How:** App side — `spring-boot-starter-actuator` + `micrometer-registry-prometheus` expose `/actuator/prometheus` in Prometheus exposition format. Cluster side — a `ServiceMonitor` resource (CRD from req 9's Prometheus Operator) labelled `release: kube-prometheus-stack` tells Prometheus to scrape that endpoint every 15s.

**Where:**
- App: [`build.gradle.kts`](build.gradle.kts) — the `micrometer-registry-prometheus` dep; [`application.properties`](src/main/resources/application.properties) — the `management.endpoints.web.exposure.include=...,prometheus` line.
- K8s: [`k8s/base/app-servicemonitor.yaml`](k8s/base/app-servicemonitor.yaml).

**Verify locally:**
```bash
curl -s localhost:8080/actuator/prometheus | head -20
# Many lines of `# TYPE ... # HELP ... <metric>{labels} <value>`
```

**Verify on cluster:**
```bash
# In Grafana → Explore → Prometheus
# Query: rate(http_server_requests_seconds_count{namespace="cloud-app"}[1m])
# Hit the app a few times → graph spikes.
```

---

## 13. Database in a separate container

> *"Database should be running on a separate container."*

**How:** PostgreSQL 16 (`postgres:16-alpine`) runs as a separate pod in its own **StatefulSet** called `postgres`. The app pod talks to it over the cluster DNS name `postgres.cloud-app.svc.cluster.local:5432`. A headless Service (`clusterIP: None`) gives the StatefulSet's pods stable DNS — required because StatefulSet pods have stable identity.

**Why StatefulSet and not Deployment:** stateful workloads need stable pod names (`postgres-0`, `postgres-1`...) and their own PVC per pod; Deployments don't guarantee either.

**Where:** [`k8s/base/postgres-statefulset.yaml`](k8s/base/postgres-statefulset.yaml), [`k8s/base/postgres-service.yaml`](k8s/base/postgres-service.yaml).

**Verify:**
```bash
kubectl -n cloud-app get statefulset,pod -l app=postgres
# → statefulset.apps/postgres   READY 1/1
# → pod/postgres-0              Running
kubectl -n cloud-app exec postgres-0 -- psql -U appuser -d appdb -c "SELECT version();"
```

---

## 14. Storage mounted to the database container

> *"Storage should be mounted to the database container."*

**How:** The StatefulSet declares `volumeClaimTemplates` that asks for a 10 GiB PVC with `storageClassName: do-block-storage`. DigitalOcean's CSI driver provisions a real block-storage volume per pod and attaches it. The PVC is mounted at `/var/lib/postgresql/data` (`subPath: pgdata`).

Because PVCs are independent of pod lifecycle, deleting the postgres pod *does not* delete the data — the new pod re-attaches to the same volume.

**Where:** [`k8s/base/postgres-statefulset.yaml`](k8s/base/postgres-statefulset.yaml) — the `volumeClaimTemplates:` block at the bottom and the `volumeMounts:` in the container spec.

**Verify storage exists:**
```bash
kubectl -n cloud-app get pvc
# → data-postgres-0   Bound   pvc-xxx   10Gi   RWO   do-block-storage
```

**Verify persistence end-to-end** (the killer demo):
```bash
# 1. Note the counter
curl -H "Host: cloud-app.${LB_IP}.nip.io" http://${LB_IP}/
#    → Hello World! Visit #N

# 2. Delete the postgres pod — StatefulSet will recreate it, attached to the same PVC
kubectl -n cloud-app delete pod -l app=postgres
kubectl -n cloud-app get pods -l app=postgres -w   # wait for Running
# Ctrl+C when it's back

# 3. Counter survives
curl -H "Host: cloud-app.${LB_IP}.nip.io" http://${LB_IP}/
#    → Hello World! Visit #N+1   ← data persisted across pod deletion
```

The same demo also passes locally — see [`local/README.md`](local/README.md), test 3.

---

## Quick-reference table

| # | Requirement | Implementation | Primary file |
|---|---|---|---|
| 1 | Docker image | distroless multi-stage | [`Dockerfile`](Dockerfile) |
| 2 | Published to registry | DOCR (free starter) | [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) |
| 3 | Deployed to K8s | Kustomize manifests | [`k8s/`](k8s/) |
| 4 | K8s on a cloud | DOKS (`fra1`, 2× s-2vcpu-2gb) | [`remote/README.md`](remote/README.md) |
| 5 | Internet-accessible | ingress-nginx + DO LB | [`k8s/base/app-ingress.yaml`](k8s/base/app-ingress.yaml) |
| 6 | Scaling | `kubectl scale` on Deployment | [`k8s/base/app-deployment.yaml`](k8s/base/app-deployment.yaml) |
| 7 | Zero-downtime updates | RollingUpdate + readiness probe | [`k8s/base/app-deployment.yaml`](k8s/base/app-deployment.yaml) |
| 8 | Rollback | `kubectl rollout undo` (history=5) | [`k8s/base/app-deployment.yaml`](k8s/base/app-deployment.yaml) |
| 9 | Monitoring | kube-prometheus-stack | [`remote/README.md`](remote/README.md) §8 |
| 10 | Autoscaling | HPA v2 (CPU 70%, mem 80%, 2–5) | [`k8s/base/app-hpa.yaml`](k8s/base/app-hpa.yaml) |
| 12a | Centralised logs | loki-stack + Promtail | [`remote/README.md`](remote/README.md) §9 |
| 12b | Metrics emission | Micrometer Prometheus + ServiceMonitor | [`k8s/base/app-servicemonitor.yaml`](k8s/base/app-servicemonitor.yaml) |
| 13 | DB in separate container | Postgres StatefulSet | [`k8s/base/postgres-statefulset.yaml`](k8s/base/postgres-statefulset.yaml) |
| 14 | Storage mounted to DB | `volumeClaimTemplates`, 10 GiB do-block-storage | [`k8s/base/postgres-statefulset.yaml`](k8s/base/postgres-statefulset.yaml) |
