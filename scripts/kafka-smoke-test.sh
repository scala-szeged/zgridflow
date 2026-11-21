#!/usr/bin/env bash
set -euo pipefail
CONTEXT="${KUBE_CONTEXT:-kind-zgridflow}"
NAMESPACE="${KAFKA_NAMESPACE:-zgridflow-local}"
POD="${KAFKA_POD:-kafka-local-0}"
TOPIC="zgridflow-smoke-$(date +%s)-$RANDOM"
MESSAGE="zgridflow-kafka-check-$TOPIC"
kubectl --context "$CONTEXT" -n "$NAMESPACE" wait --for=condition=Ready "pod/$POD" --timeout=180s
kafka() {
  kubectl --context "$CONTEXT" -n "$NAMESPACE" exec "$POD" -- "/opt/kafka/bin/$1" "${@:2}"
}
cleanup() {
  kafka kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic "$TOPIC" >/dev/null 2>&1 || true
}
kafka kafka-topics.sh --bootstrap-server localhost:9092 --create --topic "$TOPIC" --partitions 1 --replication-factor 1
trap cleanup EXIT
printf '%s\n' "$MESSAGE" | kubectl --context "$CONTEXT" -n "$NAMESPACE" exec -i "$POD" -- \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic "$TOPIC" --command-property acks=all
ACTUAL="$(kafka kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic "$TOPIC" --from-beginning --max-messages 1 --timeout-ms 30000)"
[[ "$ACTUAL" == "$MESSAGE" ]] || { echo 'Kafka message did not match' >&2; exit 1; }
echo 'Kafka produce/consume check passed.'
