#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTEXT="${KUBE_CONTEXT:-kind-zgridflow}"
REPO_URL="${1:?Usage: bootstrap/connect.sh REPO_URL [BRANCH]}"
REVISION="${2:-main}"
mkdir -p "$ROOT/.local/gitops"
VALUES="$ROOT/.local/gitops/repository.json"
# JSON is a YAML subset; encode user input without shell/YAML interpolation.
python3 - "$REPO_URL" "$REVISION" "$VALUES" <<'PY'
import json, sys
with open(sys.argv[3], 'w') as f:
    json.dump({'repoURL': sys.argv[1], 'revision': sys.argv[2], 'environment': 'local'}, f)
PY
helm template zgridflow-root "$ROOT/bootstrap/root" -f "$VALUES" > "$ROOT/.local/gitops/applications.yaml"
python3 - "$VALUES" "$ROOT/.local/gitops/root.json" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    values = json.load(f)
root = {
    'apiVersion': 'argoproj.io/v1alpha1', 'kind': 'Application',
    'metadata': {'name': 'zgridflow-root', 'namespace': 'argocd'},
    'spec': {
        'project': 'default',
        'source': {'repoURL': values['repoURL'], 'targetRevision': values['revision'],
                   'path': 'bootstrap/root', 'helm': {'valuesObject': values}},
        'destination': {'server': 'https://kubernetes.default.svc', 'namespace': 'argocd'},
        'syncPolicy': {'automated': {'prune': True, 'selfHeal': True, 'allowEmpty': False},
                       'syncOptions': ['PruneLast=true']}
    }
}
with open(sys.argv[2], 'w') as f:
    json.dump(root, f, indent=2)
PY
kubectl --context "$CONTEXT" apply -f "$ROOT/.local/gitops/root.json"
echo 'Root Application created. Check: kubectl --context kind-zgridflow -n argocd get applications'
