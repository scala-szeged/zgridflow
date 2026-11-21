#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${ZGRIDFLOW_JAVA_HOME:-/opt/homebrew/opt/openjdk@25}"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:/usr/local/bin:$HOME/.sdkman/candidates/sbt/current/bin:$PATH"
(cd "$ROOT/services/api" && sbt -batch test assembly)
(cd "$ROOT/services/gateway" && npm ci --no-audit --no-fund && npm run build && npm test)
for chart in dynamodb-local kafka-local poc-services; do helm lint "$ROOT/charts/$chart"; done
helm lint "$ROOT/bootstrap/root" --set-string repoURL=https://github.com/scala-szeged/zgridflow.git
