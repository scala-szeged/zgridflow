#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PREFIX="$(/opt/homebrew/bin/brew --prefix)"
export JENKINS_HOME="$ROOT/.local/jenkins"
export PATH="$PREFIX/bin:/usr/local/bin:$HOME/.sdkman/candidates/sbt/current/bin:$PATH"
[[ -f "$JENKINS_HOME/admin-password" ]] || { echo 'Run ./bootstrap/jenkins/install.sh first.' >&2; exit 1; }
exec "$PREFIX/opt/openjdk@21/bin/java" -Xms128m -Xmx512m \
  -Djenkins.install.runSetupWizard=false \
  -jar "$PREFIX/opt/jenkins-lts/libexec/jenkins.war" \
  --httpListenAddress=127.0.0.1 --httpPort=18080
