#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTEXT="${KUBE_CONTEXT:-kind-zgridflow}"
kubectl --context "$CONTEXT" apply --server-side --field-manager=zgridflow-bootstrap -k "$ROOT/bootstrap/argocd"
kubectl --context "$CONTEXT" -n argocd wait --for=condition=Established crd/applications.argoproj.io --timeout=120s
for deployment in $(kubectl --context "$CONTEXT" -n argocd get deployments -o name); do
  kubectl --context "$CONTEXT" -n argocd rollout status "$deployment" --timeout=300s
done
kubectl --context "$CONTEXT" -n argocd rollout status statefulset/argocd-application-controller --timeout=300s
