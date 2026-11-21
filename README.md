# ZGridFlow GitOps

Local Kubernetes on kind, with Argo CD continuously reconciling DynamoDB Local, Apache Kafka, and a minimal Scala/TypeScript service POC from Git.

- `bootstrap/argocd/`: vendored official Argo CD **v3.6.2** installation, applied with Kustomize.
- `bootstrap/root/`: Helm chart declaring the workload AppProject and Application.
- `apps/local/values.yaml`: local environment overrides.
- `charts/dynamodb-local/`: reusable Helm chart with a StatefulSet, Service, and persistent storage.
- `apps/local/kafka-values.yaml`: Kafka environment overrides.
- `charts/kafka-local/`: single persistent Kafka 4.4.0 broker/controller using KRaft.
- `services/api/`: Scala 3 + ZIO domain, DynamoDB persistence, and Kafka event relay.
- `services/gateway/`: TypeScript GraphQL queries, mutation validation, and Kafka-backed SSE subscriptions.
- `contracts/avro/`: shared, versioned Avro event schemas and a cross-language binary fixture.
- `charts/poc-services/` and `apps/local/poc-values.yaml`: application deployments and local image versions.
- `Jenkinsfile`, `ci/`, and `bootstrap/jenkins/`: local build, GitOps publishing, and Jenkins setup.

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

Kafka is useful when the application needs replayable domain events, several independent consumers, or stream processing. The sample message flow below exercises this integration while keeping DynamoDB as the state store. The real application domain can replace this sample later.

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

## Service POC

The intentionally small domain is a message with `id`, `text`, and `createdAt`. It demonstrates infrastructure integration and is not a proposed business model.

1. GraphQL `createMessage(text)` validates and trims 1–200 characters.
2. The Scala API validates again and writes the message with `published=false` in one DynamoDB item.
3. A ZIO background fiber encodes a `MessageCreated` event as Avro binary, sends it to Kafka, then marks the item published. Failed deliveries are retried from DynamoDB.
4. The gateway decodes the Avro event, validates it, and forwards the message to active GraphQL subscriptions.
5. GraphQL `messages` queries the API's DynamoDB repository, returning the newest 100 messages.

This tiny outbox uses table scans and one API replica. Delivery is at least once: a crash after publishing but before marking the item can duplicate an event. The gateway suppresses recently seen message IDs in memory. Subscriptions deliver live events and do not replay missed events after a disconnected browser reconnects; use the query to recover current data. This is a local, unauthenticated POC, not a production service. The gateway uses one Kafka consumer group per pod to fan out to that pod's subscribers.

```bash
kubectl --context kind-zgridflow -n zgridflow-local port-forward svc/gateway 4000:4000
# Open http://localhost:4000/graphql for the GraphiQL console.
```

Run these operations in separate GraphiQL tabs, starting the subscription before the mutation:

```graphql
subscription { messageCreated { id text createdAt } }
mutation { createMessage(text: "Hello from ZGridFlow") { id text createdAt } }
query { messages { id text createdAt } }
```

Queries/mutations use ordinary HTTP; subscriptions use GraphQL over Server-Sent Events. No WebSocket client is required. To test the complete deployed flow:

```bash
./scripts/poc-smoke-test.sh
```

The test checks invalid-input rejection, creates a message, queries it back from DynamoDB, and waits for its Kafka-backed subscription event. It leaves one sample message per run so persistence remains inspectable.

For development outside Kubernetes, forward DynamoDB on 8001 and Kafka on 9094 using the commands above, then run:

```bash
# Terminal 1: Scala API
cd services/api
JAVA_HOME=/opt/homebrew/opt/openjdk@25 sbt run
# Terminal 2: GraphQL gateway (uses API localhost:8080 and Kafka localhost:9094)
cd services/gateway
npm ci && npm run build && npm start
```

Backend configuration: `PORT`, `DYNAMODB_ENDPOINT`, `DYNAMODB_TABLE`, `KAFKA_BROKERS`, `KAFKA_TOPIC`. Gateway configuration: `PORT`, `API_URL`, `KAFKA_BROKERS`, `KAFKA_TOPIC`. Use separate table/topic names if running a development API alongside the Kubernetes API, since the POC relay assumes one API owner per table.

## Local Jenkins CI and Argo CD deployment

Jenkins runs directly on the Mac so builds can use the existing Docker Desktop, kind, kubeconfig, and GitHub CLI login. It binds only to loopback on port **18080**. Prerequisites for builds: Java 25, sbt, Node 26/npm, Docker, kind, kubectl, Helm, Python 3, and an authenticated `gh` account with write access to this repository. Jenkins itself uses the Java 21 runtime installed with `jenkins-lts`.

```bash
./bootstrap/jenkins/install.sh
./scripts/jenkins-local.sh
# Open http://localhost:18080/job/zgridflow-poc/
# Username: admin
# Retrieve the generated password locally:
cat .local/jenkins/admin-password
```

The initial setup creates the job and pins the top-level Jenkins plugins. Jenkins state, plugin files, password, and logs stay under ignored `.local/jenkins/`. The server stops with Ctrl+C; rerun the script after restarting your Mac. Credentials are not committed. After changing the Jenkins admin password in the UI, use the changed password for future logins; the file stores only the bootstrap password.

Click **Build Now** once to start the first build and register SCM polling. Subsequent source commits on `main` are checked every two minutes:

1. Test Scala and TypeScript, assemble the API JAR, and lint Helm charts.
2. Build images tagged with the source commit SHA and load them into kind.
3. Commit the image references to `apps/local/poc-values.yaml` and push to `main` using the Mac user's `gh` login.
4. Argo CD applies the Git change. Jenkins waits for the matching revision and healthy services, then runs the end-to-end smoke test.

The generated image-reference commit contains `[skip ci]` and is excluded from SCM polling to prevent a build loop. Manual **Build Now** requests still rebuild it, which is useful after recreating kind. Concurrent builds are disabled. If `main` advances while a build is running, publishing fails rather than overwriting newer desired state; rebuild the new revision. Jenkins refreshes and observes Argo CD but does not apply the application Deployments directly.

Only run trusted code through this local job: it executes as your Mac user with access to Docker, kubeconfig, and GitHub credentials. This POC has one local executor and no isolated build agent. GitHub remains the remote source of truth, so fetching and publishing still require connectivity.

The first deployment uses locally built `bootstrap` images. On a fresh cluster, run `./ci/validate.sh` and `./ci/build-images.sh bootstrap` before enabling the POC Application, or run Jenkins to publish SHA-tagged images. kind image storage is local; recreating the cluster requires loading those images again. `imagePullPolicy: IfNotPresent` allows the node to use these locally loaded images without a registry.

To reproduce CI validation manually:

```bash
./ci/validate.sh
```

References: [ZIO HTTP server](https://zio.dev/zio-http/reference/server), [GraphQL Yoga subscriptions](https://the-guild.dev/graphql/yoga-server/docs/features/subscriptions), [Jenkins pipelines](https://www.jenkins.io/doc/book/pipeline/getting-started/).

## Avro Kafka contract

`contracts/avro/message-created-v1.avsc` is the shared source of truth for the Scala producer and TypeScript gateway. The API packages it as a classpath resource; the gateway image includes the same file. Apache Avro 1.12.2 writes the event and `avsc` 5.7.9 reads it. REST, GraphQL, and DynamoDB payloads continue to use JSON.

The topic is **`zgridflow.message-created.avro.v1`**. Each Kafka record uses the message UUID as its string key and raw Avro binary as its value. Three UTF-8 headers identify the encoding and exact writer schema:

- `content-type`: `application/avro`
- `avro-schema`: `dev.zgridflow.events.v1.MessageCreated`
- `avro-schema-sha256`: SHA-256 of the exact shared `.avsc` file bytes, with LF line endings.

This is raw Avro binary with Kafka headers, not Avro Object Container Files, Avro single-object framing, or Confluent's schema-ID framing. No schema registry is installed. Both services ship the one supported writer schema, and the gateway rejects unknown/missing schema headers, truncated data, trailing bytes, and invalid domain values. Invalid records are logged and skipped in this POC; a production service should have an explicit quarantine/dead-letter policy.

Keep the published v1 schema immutable. Add a new schema version and coordinate readers/writers for future changes. A schema registry and enforced compatibility checks become useful when real services need independent releases and multiple schema versions.

The original `zgridflow.message-created` JSON topic is preserved. Jenkins publishes the new topic configuration together with the Avro-capable image references, avoiding a configuration-only switch while old images are still deployed. Existing messages remain queryable in DynamoDB; previously delivered events are not replayed into the Avro topic. Any pending outbox items are encoded with Avro on their next delivery attempt.

The shared fixture under `contracts/avro/fixtures/` includes a Unicode message, expected wire bytes, and schema hash. Scala and TypeScript tests both verify that fixture. `./ci/validate.sh` runs these contract tests; `./scripts/poc-smoke-test.sh` verifies the actual Scala → Avro/Kafka → TypeScript → GraphQL subscription path after deployment.

If building the gateway image manually, use the repository root as Docker's build context so the shared schema is available:

```bash
docker build -f services/gateway/Dockerfile -t zgridflow-gateway:dev .
```

Reference: [Apache Avro specification](https://avro.apache.org/docs/1.12.0/specification/).
