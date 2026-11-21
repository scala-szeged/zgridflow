#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BREW="${BREW:-/opt/homebrew/bin/brew}"
"$BREW" list jenkins-lts >/dev/null 2>&1 || "$BREW" install jenkins-lts
PREFIX="$("$BREW" --prefix)"
WAR="$PREFIX/opt/jenkins-lts/libexec/jenkins.war"
JAVA="$PREFIX/opt/openjdk@21/bin/java"
mkdir -p "$ROOT/.local/tools" "$ROOT/.local/jenkins/init.groovy.d"
MANAGER="$ROOT/.local/tools/jenkins-plugin-manager-2.16.0.jar"
if [[ ! -f "$MANAGER" ]]; then
  curl -fL --retry 2 https://github.com/jenkinsci/plugin-installation-manager-tool/releases/download/2.16.0/jenkins-plugin-manager-2.16.0.jar -o "$MANAGER"
fi
"$JAVA" -jar "$MANAGER" --war "$WAR" \
  --plugin-file "$ROOT/bootstrap/jenkins/plugins.txt" \
  --plugin-download-directory "$ROOT/.local/jenkins/plugins"
if [[ ! -f "$ROOT/.local/jenkins/admin-password" ]]; then
  (umask 077; openssl rand -hex 24 > "$ROOT/.local/jenkins/admin-password")
fi
cp "$ROOT/bootstrap/jenkins/init.groovy" "$ROOT/.local/jenkins/init.groovy.d/zgridflow.groovy"
echo 'Jenkins configured. Start with ./scripts/jenkins-local.sh'
