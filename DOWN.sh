#!/usr/bin/env bash
set -euo pipefail

CLUSTER="${1:-adrian-doks}"
REGISTRY="${2:-adrian-cloud-app}"

echo ">>> Uninstalling ingress-nginx first so DigitalOcean releases the LB IP:"
helm uninstall ingress-nginx -n ingress-nginx || true

echo ">>> Deleting DOKS cluster $CLUSTER (this also frees PVCs):"
doctl kubernetes cluster delete "$CLUSTER" --force --dangerous

echo ">>> Deleting DOCR registry $REGISTRY:"
doctl registry delete "$REGISTRY" --force

echo ">>> Done. Verify in https://cloud.digitalocean.com that nothing remains."
