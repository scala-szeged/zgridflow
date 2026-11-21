# ZGridFlow GitOps

Local Kubernetes on kind, with Argo CD continuously reconciling DynamoDB Local from Git.

- `bootstrap/argocd/`: vendored official Argo CD **v3.6.2** installation, applied with Kustomize.
- `bootstrap/root/`: Helm chart declaring the workload AppProject and Application.
- `apps/local/values.yaml`: local environment overrides.
- `charts/dynamodb-local/`: reusable Helm chart with a StatefulSet, Service, and persistent storage.

## Bootstrap

Prerequisites: a running `kind-zgridflow` cluster, kubectl, Helm, Python 3, and Git. The source repository is `https://github.com/scala-szeged/zgridflow.git`, tracked on branch `main`.

```bash
./bootstrap/install.sh
./bootstrap/connect.sh https://github.com/scala-szeged/zgridflow.git main
kubectl --context kind-zgridflow -n argocd get applications
```

For a private repository, register read-only repository credentials with Argo CD before connecting; keep credentials out of Git. The root Application uses the default Argo CD project to manage the child AppProject and Application. The child project permits workload resources only in `zgridflow-local`.

After connecting, edit `apps/local/values.yaml` or the chart, commit and push to the tracked branch. Argo CD renders Helm and automatically syncs, prunes removed workload resources, and repairs drift. Detection normally happens on the polling interval; an optional Git webhook can accelerate it. Local edits and commits that are not pushed are not visible to Argo CD.

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
```

References: [Argo CD installation](https://argo-cd.readthedocs.io/en/stable/getting_started/), [Git-sourced Helm values](https://argo-cd.readthedocs.io/en/stable/user-guide/helm/).
