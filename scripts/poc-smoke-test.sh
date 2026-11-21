#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTEXT="${KUBE_CONTEXT:-kind-zgridflow}"
PORT="${POC_TEST_PORT:-14000}"
NAMESPACE="${POC_NAMESPACE:-zgridflow-local}"
LOG="$(mktemp)"
kubectl --context "$CONTEXT" -n "$NAMESPACE" port-forward svc/gateway "$PORT:4000" > "$LOG" 2>&1 &
FORWARD_PID=$!
cleanup() { kill "$FORWARD_PID" 2>/dev/null || true; wait "$FORWARD_PID" 2>/dev/null || true; rm -f "$LOG"; }
trap cleanup EXIT
for attempt in {1..60}; do
  if ! kill -0 "$FORWARD_PID" 2>/dev/null; then cat "$LOG" >&2; exit 1; fi
  if curl -fsS "http://localhost:$PORT/ready" >/dev/null 2>&1; then break; fi
  sleep 1
done
node "$ROOT/scripts/poc-smoke-test.mjs" "http://localhost:$PORT/graphql"
