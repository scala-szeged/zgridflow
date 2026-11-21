# ZGridFlow GitOps

Local Kubernetes on kind, with Argo CD continuously reconciling DynamoDB Local and Apache Kafka from Git.

- `bootstrap/argocd/`: vendored official Argo CD **v3.6.2** installation, applied with Kustomize.
- `bootstrap/root/`: Helm chart declaring the workload AppProject and Application.
- `apps/local/values.yaml`: local environment overrides.
- `charts/dynamodb-local/`: reusable Helm chart with a StatefulSet, Service, and persistent storage.
- `apps/local/kafka-values.yaml`: Kafka environment overrides.
- `charts/kafka-local/`: single persistent Kafka 4.4.0 broker/controller using KRaft.

## Bootstrap

Prerequisites: a running `kind-zgridflow` cluster, kubectl, Helm, Python 3, and Git. The source repository is `https://github.com/scala-szeged/zgridflow.git`, tracked on branch `main`.

```bash
./bootstrap/install.sh
./bootstrap/connect.sh https://github.com/scala-szeged/zgridflow.git main
kubectl --context kind-zgridflow -n argocd get applications
```

For a private repository, register read-only repository credentials with Argo CD before connecting; keep credentials out of Git. The root Application uses the default Argo CD project to manage the child AppProject and Application. The child project permits workload resources only in `zgridflow-local`.

After connecting, edit the values under `apps/local/` or a chart, commit and push to the tracked branch. Argo CD renders Helm and automatically syncs, prunes removed workload resources, and repairs drift. Detection normally happens on the polling interval; an optional Git webhook can accelerate it. Local edits and commits that are not pushed are not visible to Argo CD.

The root Application's repository and branch are bootstrap inputs. Run `connect.sh` again to change them. Argo CD itself is upgraded explicitly by updating the vendored installation and rerunning `install.sh`.

## Access

```bash
# Argo CD UI: https://localhost:8080 (local self-signed certificate)
kubectl --context kind-zgridflow -n argocd port-forward svc/argocd-server 8080:443
# In another terminal, retrieve the initial admin password locally:
argocd admin initial-password --context kind-zgridflow -n argocd

# Kubernetes DynamoDB: http://localhost:8001
kubectl --context kind-zgridflow -n zgridflow-local port-forward svc/dynamodb-local 8001:8000
```

Use username `admin` for Argo CD. Change the initial password after login and remove `argocd-initial-admin-secret` once changed.

Within Kubernetes, DynamoDB is at `http://dynamodb-local.zgridflow-local.svc.cluster.local:8000`. Use dummy AWS credentials and an explicit region such as `us-east-1` with the local endpoint.

`scripts/dynamodb-local.sh` continues to run the standalone Java database on port 8000. The Kubernetes database has a separate, initially empty volume; no existing data is migrated. Kubernetes PVCs are retained when the StatefulSet is deleted, but deleting the kind cluster destroys its local storage. Changing volume size or storage class on an existing StatefulSet requires a separate storage migration.

## Validation

```bash
helm lint charts/dynamodb-local -f apps/local/values.yaml
helm template dynamodb-local charts/dynamodb-local -n zgridflow-local -f apps/local/values.yaml
helm lint bootstrap/root --set-string repoURL=https://example.com/org/repo.git
helm lint charts/kafka-local -f apps/local/kafka-values.yaml
./scripts/kafka-smoke-test.sh
```

References: [Argo CD installation](https://argo-cd.readthedocs.io/en/stable/getting_started/), [Git-sourced Helm values](https://argo-cd.readthedocs.io/en/stable/user-guide/helm/).

## Local Kafka

Kafka is useful when the application needs replayable domain events, several independent consumers, or stream processing. This repository currently contains infrastructure only, so Kafka is an available development dependency; no application integration has been added. Keep DynamoDB as the state store, and introduce Kafka topics when a concrete producer/consumer flow exists. Writing the same change to both systems requires an explicit consistency/retry strategy.

The local setup uses one combined broker/controller, a 512 MiB maximum JVM heap, and a 2 GiB PVC. It needs no ZooKeeper or operator. It is intended for development: plaintext listeners, replication factor one, and no broker failover. Topics must be created explicitly. Default retention is 24 hours or 1 GiB per partition, with 128 MiB segments; retention is asynchronous and these are per-partition limits, so size the volume for the number of partitions and internal topics. Preserve `clusterId` while reusing a PVC. PVCs survive StatefulSet deletion, but deleting the kind cluster destroys its local data.

Kubernetes applications use `kafka-local.zgridflow-local.svc.cluster.local:9092`. For an application running on your Mac:

```bash
kubectl --context kind-zgridflow -n zgridflow-local port-forward svc/kafka-local 9094:9094
# Set your application's bootstrap.servers to localhost:9094.
```

The external listener advertises `localhost:9094`, so keep that same local port. Forwarding the internal listener would return Kubernetes DNS addresses that a Mac client cannot resolve. To change the port, update both `external.port` in `apps/local/kafka-values.yaml` and the forwarding command.

Create a topic from the broker's bundled CLI:

```bash
kubectl --context kind-zgridflow -n zgridflow-local exec kafka-local-0 -- \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic zgridflow-events --partitions 1 --replication-factor 1
./scripts/kafka-smoke-test.sh
```

The smoke test creates a unique temporary topic, produces and consumes one message, checks its contents, and deletes that topic. Set `kafka.enabled: false` in `bootstrap/root/values.yaml` and push to remove the Kafka Application and its StatefulSet and Services. The StatefulSet's retention policy preserves the PVC for reuse.

References: [Apache Kafka quickstart](https://kafka.apache.org/quickstart/), [official Docker configuration examples](https://github.com/apache/kafka/tree/4.4.0/docker/examples).
