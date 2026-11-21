#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DYNAMODB_DIR="$PROJECT_DIR/.local/dynamodb"

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif [[ -x "/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/java" ]]; then
  JAVA_BIN="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/java"
else
  JAVA_BIN=java
fi

mkdir -p "$DYNAMODB_DIR/data"
cd "$DYNAMODB_DIR"
exec "$JAVA_BIN" -Djava.library.path="$DYNAMODB_DIR/DynamoDBLocal_lib" \
  -jar "$DYNAMODB_DIR/DynamoDBLocal.jar" \
  -sharedDb -dbPath "$DYNAMODB_DIR/data" -port "${DYNAMODB_PORT:-8000}" "$@"
