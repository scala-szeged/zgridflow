#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TAG="${1:?Usage: ci/publish-desired-state.sh IMAGE_TAG}"
[[ "$TAG" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]] || { echo 'Invalid image tag' >&2; exit 1; }
# Fetch first: never overwrite another developer's newer desired state.
git fetch origin main
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || {
  echo 'main advanced while building; rerun CI on the newer commit.' >&2; exit 1;
}
python3 - "$TAG" <<'PY'
import json, sys
# JSON is valid YAML, and avoids adding a YAML parser dependency to the host.
with open('apps/local/poc-values.yaml', 'w') as f:
    json.dump({'api': {'image': 'zgridflow-api', 'tag': sys.argv[1]},
               'gateway': {'image': 'zgridflow-gateway', 'tag': sys.argv[1]},
               'kafka': {'topic': 'zgridflow.message-created.avro.v1'}}, f, indent=2)
    f.write('\n')
PY
git add apps/local/poc-values.yaml
if git diff --cached --quiet; then exit 0; fi
git -c user.name='ZGridFlow local CI' -c user.email='ci@zgridflow.local' commit -m "Deploy POC images $TAG [skip ci]"
# Reuse the Mac user's existing gh login; no token is stored in the repository.
git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push origin HEAD:main
