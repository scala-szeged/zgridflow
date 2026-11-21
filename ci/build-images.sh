#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="${1:?Usage: ci/build-images.sh IMAGE_TAG}"
[[ "$TAG" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]] || { echo 'Invalid image tag' >&2; exit 1; }
docker build -t "zgridflow-api:$TAG" "$ROOT/services/api"
docker build -f "$ROOT/services/gateway/Dockerfile" -t "zgridflow-gateway:$TAG" "$ROOT"
kind load docker-image --name "${KIND_CLUSTER:-zgridflow}" "zgridflow-api:$TAG" "zgridflow-gateway:$TAG"
