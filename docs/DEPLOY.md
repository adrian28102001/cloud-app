# Deployment Runbook — cloud-app on DigitalOcean Kubernetes

This is a one-time bootstrap. After it's done, deployments happen automatically via GitHub Actions on every push to `main`.

## 0. Prerequisites

- DigitalOcean account with billing enabled (the **$200 / 60-day** new-user credit covers this lab).
- `doctl`, `kubectl`, `helm`, `kustomize`, `docker` installed locally.
- A DigitalOcean Personal Access Token (PAT) with **read + write** scope:
  https://cloud.digitalocean.com/account/api/tokens
- Authenticate doctl:
  ```bash
  doctl auth init --access-token <PAT>
  ```

## 1. Create the DOCR registry (free tier)

```bash
doctl registry create adrian-cloud-app --subscription-tier starter --region fra1
```

`starter` tier = free, 500 MB. The registry name (e.g. `adrian-cloud-app`) is plugged into manifests + GitHub variables in step 4 and 10.

## 2. Create the DOKS cluster

```bash
doctl kubernetes cluster create adrian-doks \
  --region fra1 \
  --version latest \
  --size s-2vcpu-2gb \
  --count 2 \
  --auto-upgrade=false \
  --wait
```

Takes ~5 min. Sets your kubectl context to the new cluster.

## 3. Grant DOKS permission to pull from DOCR

```bash
doctl kubernetes cluster registry add adrian-doks
```

This creates an `imagePullSecret` named `registry-adrian-cloud-app` in every namespace and patches the default ServiceAccount to use it. So you don't need `imagePullSecrets:` in the Deployment.

## 4. Substitute placeholders in the repo

Replace the literal string `REPLACE_ME`:

```bash
grep -rl REPLACE_ME k8s/ | xargs sed -i.bak 's/REPLACE_ME/adrian-cloud-app/g'
rm k8s/**/*.bak  # tidy up
```

Commit + push, so GitHub Actions sees the substituted manifests on its next run.

## 5. Apply the namespace

```bash
kubectl apply -f k8s/base/namespace.yaml
```

## 6. Apply the Postgres secret (REAL credentials, not the example file)

Generate a random password and apply:

```bash
cat <<EOF | kubectl -n cloud-app apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: postgres-secret
type: Opaque
stringData:
  POSTGRES_USER: appuser
  POSTGRES_PASSWORD: $(openssl rand -base64 24)
  POSTGRES_DB: appdb
EOF
```

> Don't commit this. `k8s/base/postgres-secret.example.yaml` is the placeholder version (intentionally NOT in the kustomization).

## 7. Install ingress-nginx

```bash
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo update
helm install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace ingress-nginx --create-namespace \
  --set controller.publishService.enabled=true
```

Wait for the external LB IP:

```bash
kubectl -n ingress-nginx get svc ingress-nginx-controller -w
```

When `EXTERNAL-IP` appears (~2 min), copy it. Then update the ingress host:

```bash
LB_IP=$(kubectl -n ingress-nginx get svc ingress-nginx-controller \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
sed -i.bak "s/cloud-app.REPLACE_ME.nip.io/cloud-app.${LB_IP}.nip.io/" k8s/base/app-ingress.yaml
rm k8s/base/app-ingress.yaml.bak
git commit -am "chore: pin ingress host to LB IP"
```

(`nip.io` is a public wildcard DNS — `cloud-app.1.2.3.4.nip.io` resolves to `1.2.3.4`. No domain purchase needed.)

## 8. Install kube-prometheus-stack (Prometheus + Grafana + Alertmanager)

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace monitoring --create-namespace \
  --set grafana.adminPassword=admin
```

The release name `kube-prometheus-stack` matches the `release:` label on the ServiceMonitor, so Prometheus auto-discovers the app's `/actuator/prometheus` endpoint.

Open Grafana via port-forward:
```bash
kubectl -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80
# → http://localhost:3000  user: admin / pass: admin
```

## 9. Install loki-stack (centralized logging)

```bash
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update
helm install loki grafana/loki-stack \
  --namespace monitoring \
  --set grafana.enabled=false \
  --set promtail.enabled=true \
  --set loki.persistence.enabled=true \
  --set loki.persistence.storageClassName=do-block-storage \
  --set loki.persistence.size=10Gi
```

In Grafana, add data source: **Loki** → URL `http://loki:3100`.
Then Explore → query `{namespace="cloud-app"}` to tail app logs in real time.

## 10. Configure GitHub Actions secrets and variables

In your repo Settings → Secrets and variables → Actions:

| Type     | Name                          | Value                     |
|----------|-------------------------------|---------------------------|
| Secret   | `DIGITALOCEAN_ACCESS_TOKEN`   | the PAT from step 0       |
| Variable | `DOCR_NAME`                   | `adrian-cloud-app`        |
| Variable | `DOKS_CLUSTER`                | `adrian-doks`             |

## 11. First manual deploy

The CI workflow does `kubectl set image` on an *existing* Deployment, so the Deployment must exist before the first CI run. Bootstrap once:

```bash
# Build + push locally for the first roll:
doctl registry login
docker build -t registry.digitalocean.com/adrian-cloud-app/cloud-app:bootstrap .
docker push registry.digitalocean.com/adrian-cloud-app/cloud-app:bootstrap

# Patch the placeholder image tag and apply the whole bundle:
sed -i.bak 's|:latest|:bootstrap|' k8s/base/app-deployment.yaml
kubectl apply -k k8s/overlays/prod
mv k8s/base/app-deployment.yaml.bak k8s/base/app-deployment.yaml  # revert for CI
```

Watch:
```bash
kubectl -n cloud-app get pods -w
```

## 12. Verify it works end-to-end

```bash
LB_IP=$(kubectl -n ingress-nginx get svc ingress-nginx-controller \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
curl -H "Host: cloud-app.${LB_IP}.nip.io" http://${LB_IP}/
# → Hello World! Visit #1
curl -H "Host: cloud-app.${LB_IP}.nip.io" http://${LB_IP}/
# → Hello World! Visit #2
```

## 13. Demo checklist (for the instructor)

| What to show | Command |
|---|---|
| Cluster healthy | `kubectl get nodes,pods,svc,ingress,hpa,pvc -A` |
| Counter increments | `for i in {1..5}; do curl -H "Host: cloud-app.${LB_IP}.nip.io" http://${LB_IP}/; echo; done` |
| **Counter survives app pod restart** (proves PVC works through the DB) | `kubectl -n cloud-app delete pod -l app=cloud-app` then curl again |
| **Counter survives DB pod restart** (proves PVC really persists) | `kubectl -n cloud-app delete pod -l app=postgres` then wait, then curl |
| **Zero-downtime rolling update** | Push a code change → watch GitHub Action → simultaneously: `while true; do curl ...; sleep 0.5; done` (no 5xx) |
| **Rollback** | `kubectl -n cloud-app rollout undo deployment/cloud-app` |
| **Manual scale** | `kubectl -n cloud-app scale deploy/cloud-app --replicas=4` |
| **Autoscale under load** | `hey -z 60s -c 50 http://${LB_IP}/` (with Host header) + `kubectl -n cloud-app get hpa -w` |
| **Metrics in Grafana** | port-forward Grafana → Explore → Prometheus → `rate(http_server_requests_seconds_count{namespace="cloud-app"}[1m])` |
| **Centralized logs in Loki** | same Grafana → Explore → Loki → `{namespace="cloud-app"}` |

## 14. Cleanup (avoid surprise bills!)

```bash
./DOWN.sh
```

Verify in https://cloud.digitalocean.com that no Droplets, Load Balancers, Volumes, or Registries remain.
