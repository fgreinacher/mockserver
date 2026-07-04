## Install MockServer Helm Chart

### Prerequisites

- A Kubernetes cluster
- [`kubectl`](https://kubernetes.io/docs/tasks/tools/) configured to talk to it
- [Helm](https://helm.sh/docs/intro/quickstart/) 3.8 or later (OCI support is enabled by default from 3.8)

### Helm Install

The chart is published to the **GitHub Container Registry (GHCR) as an OCI artifact** — no `helm repo add` is needed, `helm install` can pull straight from `oci://`:

```bash
helm upgrade --install --create-namespace --namespace mockserver --version 7.4.0 mockserver oci://ghcr.io/mock-server/charts/mockserver
```

The OCI artifact is public — no authentication is required for `helm pull` / `helm install`. Browse the published versions on the [GHCR package page](https://github.com/orgs/mock-server/packages/container/package/charts%2Fmockserver) and pass the one you want with `--version` (omit `--version` to get the latest).

**OR** install from the legacy `.tgz` served by `www.mock-server.com`:

```bash
helm upgrade --install --create-namespace --namespace mockserver mockserver https://www.mock-server.com/mockserver-7.4.0.tgz
```

**OR** if you have the helm chart source folder (i.e. you have the repository cloned):

```bash
helm upgrade --install --create-namespace --namespace mockserver mockserver helm/mockserver
```

### Verifying the chart signature

The OCI chart is signed with [cosign](https://github.com/sigstore/cosign) using MockServer's signing key, published at **https://www.mock-server.com/mockserver-cosign.pub** (the same key signs the Docker images):

```bash
cosign verify \
  --key https://www.mock-server.com/mockserver-cosign.pub \
  ghcr.io/mock-server/charts/mockserver:7.4.0
```

Each of the commands above installs MockServer into a **namespace** called `mockserver` with default configuration (as per the embedded [values.yaml](https://github.com/mock-server/mockserver-monorepo/blob/master/helm/mockserver/values.yaml)).  
MockServer will then be available on domain name `mockserver.mockserver.svc.cluster.local`, as long as the namespace you are calling from isn't prevented (by network policy) to call the `mockserver` namespace.

**THEN**

To view the logs:

```bash
kubectl -n mockserver logs --tail=100 -l app=mockserver,release=mockserver
```

To wait until the deployment is complete run:

```bash
kubectl -n mockserver rollout status deployments mockserver
```

To check the status of the deployment without waiting, run the following command and confirm the `mockserver` has the `Running` status:

```bash 
kubectl -n mockserver get po -l release=mockserver
```

### Basic MockServer Configuration 

Modify the arguments MockServer starts with by setting values explicitly using `--set`, as follows:

```bash
helm upgrade --install --create-namespace --namespace mockserver --set app.serverPort=1080 --set app.logLevel=INFO mockserver oci://ghcr.io/mock-server/charts/mockserver
```

The following values are supported:
- `app.serverPort` (default: 1080)
- `app.logLevel` (default: INFO)
- `app.proxyRemoteHost` (no default)
- `app.proxyRemotePort` (no default)
- `app.jvmOptions` (default: `""`) - extra JVM options passed via `JAVA_TOOL_OPTIONS`. The image already caps the heap at 75% of the container memory limit (`-XX:MaxRAMPercentage=75.0`); set an explicit `-Xmx` here to change it. Always set `resources.limits.memory` so the heap is sized off a bounded container limit rather than total node memory.
- `image.snapshot` (default: false) - set `true` to use latest snapshot version

For example configure a proxyRemoteHost and proxyRemotePort, as follows:

```bash
helm upgrade --install --create-namespace --namespace mockserver --set app.serverPort=1080 --set app.proxyRemoteHost=www.mock-server.com --set app.proxyRemotePort=443 mockserver oci://ghcr.io/mock-server/charts/mockserver
```

Double check the correct arguments have been passed to the pod, as follows:

```bash
kubectl -n mockserver logs -l app=mockserver,release=mockserver
``` 

### Detailed MockServer Configuration

There are two ways to provide MockServer configuration (properties file, expectation initialization JSON, TLS certificates, etc.):

1. **Inline configuration** (recommended) — provide configuration directly in `values.yaml`
2. **External ConfigMap** — create a ConfigMap separately (manually, via CI, or using the example [mockserver-config](https://github.com/mock-server/mockserver-monorepo/tree/master/helm/mockserver-config) chart)

Both approaches mount configuration into the container at `/config`. See [MockServer Configuration](https://www.mock-server.com/mock_server/configuration_properties.html) for details of all configuration options.

#### Option 1: Inline Configuration (single chart install)

Set `app.config.enabled=true` and provide configuration content directly in values. This creates a ConfigMap as part of the main chart — no separate chart or manual ConfigMap creation is needed.

Using `--set-string` (commas in JSON must be escaped as `\,`):

```bash
helm upgrade --install --create-namespace --namespace mockserver \
  --set app.config.enabled=true \
  --set app.config.properties="mockserver.initializationJsonPath=/config/initializerJson.json" \
  --set-string 'app.config.initializerJson=[{"httpRequest":{"path":"/example"}\,"httpResponse":{"body":"response"}}]' \
  mockserver helm/mockserver
```

> **Note:** Helm's `--set` flag treats commas as key/value separators, which corrupts JSON values. Use `--set-string` with escaped commas (`\,`) for simple cases, or a `values.yaml` file (recommended) for anything non-trivial.

Or using a `values.yaml` file (recommended for complex configuration):

```yaml
app:
  config:
    enabled: true
    properties: |
      mockserver.initializationJsonPath=/config/initializerJson.json
      mockserver.enableCORSForAPI=true
      mockserver.enableCORSForAllResponses=true
    initializerJson: |
      [
        {
          "httpRequest": { "path": "/example" },
          "httpResponse": { "body": "some response" }
        }
      ]
```

```bash
helm upgrade --install --create-namespace --namespace mockserver -f values.yaml mockserver helm/mockserver
```

The following inline config values are supported:
- `app.config.enabled` (default: false) - set `true` to create a ConfigMap from inline values
- `app.config.properties` (default: "") - content of `mockserver.properties`
- `app.config.initializerJson` (default: "") - content of `initializerJson.json`
- `app.config.extraFiles` (default: {}) - map of additional filenames to content (e.g. TLS certificates)

#### Option 2: External ConfigMap

If a ConfigMap called `mockserver-config` (or a custom name) exists in the same namespace, it will be mounted into the MockServer container at `/config`.
This ConfigMap can contain a `mockserver.properties` file and other related configuration files such as:
- [json expectation initialization](https://www.mock-server.com/mock_server/initializing_expectations.html), or
- custom [TLS CA, X.509 Certificate or Private Key](https://www.mock-server.com/mock_server/HTTPS_TLS.html#configuration)

The `mockserver.properties` file should load these additional files from the directory `/config` which is the `mountPath` for the ConfigMap.

The mapping of the configuration ConfigMap can be configured as follows:
- `app.mountedConfigMapName` (default: mockserver-config) - name of the configuration ConfigMap (in the same namespace) to mount
- `app.propertiesFileName` (default: mockserver.properties) - path of the property file in the ConfigMap

For example:

```bash
helm upgrade --install --create-namespace --namespace mockserver --set app.mountedConfigMapName=other-mockserver-config --set app.propertiesFileName=other-mockserver.properties mockserver helm/mockserver
```

An example of a helm chart to create this ConfigMap is [helm/mockserver-config](https://github.com/mock-server/mockserver-monorepo/tree/master/helm/mockserver-config)

### Extending MockServer Classpath

To use [class callbacks](https://www.mock-server.com/mock_server/creating_expectations.html#button_response_class_callback) or an [expectation initializer class](https://www.mock-server.com/mock_server/initializing_expectations.html#expectation_initializer_class) the classpath for MockServer must include the specified classes.
To support adding classes to the classpath if a configmap called `mockserver-config` exists in the same namespace any jar files contained in this configmap will be added into MockServer classpath.

The mapping of the libs configmap can be configured as follows: 
- `app.mountedLibsConfigMapName` (default: mockserver-config) - name of the libs configmap (in the same namespace) to mount

For example:

```bash
helm upgrade --install --create-namespace --namespace mockserver --set app.mountedLibsConfigMapName=mockserver-libs mockserver helm/mockserver
```

### Persistent Storage

By default, expectations are held in memory and lost when a pod restarts. To persist expectations across pod restarts, enable persistent storage:

```bash
helm upgrade --install --namespace mockserver \
  --set app.persistence.enabled=true \
  mockserver helm/mockserver
```

This creates a PersistentVolumeClaim and automatically configures MockServer to persist and reload expectations from it. No additional configuration is needed.

The following persistence values are supported:
- `app.persistence.enabled` (default: false) — enable persistent storage
- `app.persistence.existingClaimName` (default: "") — use an existing PVC instead of creating one
- `app.persistence.storageClass` (default: "") — StorageClass for the PVC (empty = cluster default)
- `app.persistence.accessModes` (default: [ReadWriteOnce]) — PVC access modes
- `app.persistence.size` (default: 256Mi) — PVC size
- `app.persistence.mountPath` (default: /persistence) — mount path inside the container
- `app.persistence.annotations` (default: {}) — annotations for the PVC

When persistence is enabled, the chart automatically sets `MOCKSERVER_PERSIST_EXPECTATIONS`, `MOCKSERVER_PERSISTED_EXPECTATIONS_PATH`, and `MOCKSERVER_INITIALIZATION_JSON_PATH` environment variables. These are safe defaults — any matching property in your `mockserver.properties` file takes precedence.

**Note:** Chart-managed PVCs are NOT deleted by `helm uninstall`. Delete the PVC manually if you want to remove persisted data.

**Pod securityContext / PVC permissions:** if the pod cannot write to the mounted volume (a common cause of persistence failing on clusters with restrictive defaults), set a pod-level `fsGroup` via `podSecurityContext`, which is rendered verbatim into the Deployment's `spec.template.spec.securityContext`:

```bash
helm upgrade --install --namespace mockserver mockserver oci://ghcr.io/mock-server/charts/mockserver \
  --set app.persistence.enabled=true \
  --set podSecurityContext.fsGroup=2000
```

`podSecurityContext` accepts any pod-level `securityContext` fields (`fsGroup`, `fsGroupChangePolicy`, `runAsGroup`, `seccompProfile`, …) and defaults to `{}` (nothing emitted).

See the [full persistence documentation](https://www.mock-server.com/where/kubernetes.html#helm_persistent_storage) for more examples including existing PVC usage and clustering with shared storage.

### Chaos Proxy (fault injection)

MockServer can be deployed as a **chaos proxy** in front of a real upstream Service to inject faults (errors, latency, dropped connections, slow/corrupted responses, rate limits) into the responses your services receive — without changing the calling code. Point a service at this MockServer Service instead of the real upstream (or use it as an egress/forward proxy), and attach an `HttpChaosProfile` (`chaos` block) to a forwarding expectation.

A minimal reverse-proxy example, supplied through the chart's inline configuration:

```yaml
# values-chaos.yaml
app:
  config:
    enabled: true
    properties: |
      mockserver.initializationJsonPath=/config/initializerJson.json
    initializerJson: |
      [
        {
          "httpRequest": { "path": "/.*" },
          "httpForward": { "host": "upstream.default.svc.cluster.local", "port": 8080 },
          "chaos": {
            "errorStatus": 503,
            "errorProbability": 0.3,
            "retryAfter": "5",
            "latency": { "timeUnit": "MILLISECONDS", "value": 500 }
          },
          "times": { "unlimited": true }
        }
      ]
```

```bash
helm upgrade --install --namespace mockserver \
  -f values-chaos.yaml \
  mockserver helm/mockserver
```

The `/.*` request matcher means every path is forwarded to the upstream with chaos applied, so no separate `mockserver.proxyRemoteHost` is required. (If `app.persistence.enabled` is also `true`, drop the `mockserver.initializationJsonPath` line — the chart points the initialization path at the persisted-expectations file automatically.)

The `chaos` block supports probabilistic errors, latency, connection drops, count-based and time-based outage windows, response-body corruption, slow (dribbled) responses, and a stateful request quota. See the [Chaos Testing & Fault Injection](https://www.mock-server.com/mock_server/chaos_testing.html) reference and the [Chaos Proxy in Kubernetes](https://www.mock-server.com/mock_server/chaos_testing_kubernetes.html) guide (reverse-proxy, egress/forward-proxy, and sidecar patterns, plus CA-trust setup for TLS interception).

### MockServer URL

#### Local Kubernetes Cluster (i.e. [minikube](https://github.com/kubernetes/minikube), [microk8s](https://microk8s.io/))

If the `service` type hasn't been modified the following will provide the MockServer URL from outside the cluster.

```bash
export NODE_PORT=$(kubectl get -n mockserver -o jsonpath="{.spec.ports[0].nodePort}" services mockserver)
export NODE_IP=$(kubectl get nodes -n mockserver -o jsonpath="{.items[0].status.addresses[0].address}")
export MOCKSERVER_HOST=$NODE_IP:$NODE_PORT
echo http://$MOCKSERVER_HOST
```

To test the installation the following `curl` command should return the ports MockServer is bound to:

```bash
curl -v -X PUT http://$MOCKSERVER_HOST/mockserver/status
```

#### Docker for Desktop

[Docker Desktop](https://www.docker.com/products/docker-desktop) automatically exposes **LoadBalancer** services.  
On macOS Docker Desktop runs inside a lightweight VM, so the node IP address is not reachable from the host; the only way to call services is via the exposed **LoadBalancer** service added by Docker Desktop.

To ensure that Docker for Desktop exposes MockServer update the service type to **LoadBalancer** using **--set service.type=LoadBalancer** and set the exposed port using **--set service.port=1080**, as follows:

```bash
helm upgrade --install --create-namespace --namespace mockserver --set service.type=LoadBalancer --set service.port=1080 mockserver oci://ghcr.io/mock-server/charts/mockserver
```

MockServer will then be reachable on **http://localhost:1080**

For **LoadBalancer** services it is possible to query kubernetes to programmatically determine the MockServer base URL as follows:

```bash
export SERVICE_IP=$(kubectl get svc --namespace mockserver mockserver -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
export MOCKSERVER_HOST=$SERVICE_IP:1080
echo http://$MOCKSERVER_HOST
```

#### Outside Remote Kubernetes Cluster (i.e. Azure AKS, AWS EKS, etc)

```bash
kubectl -n mockserver port-forward svc/mockserver 1080:1080 &
export MOCKSERVER_HOST=127.0.0.1:1080
echo http://$MOCKSERVER_HOST
```

#### Inside Kubernetes Cluster

If a [DNS server](https://kubernetes.io/docs/concepts/services-networking/service/#dns) has been installed in the Kubernetes cluster the following DNS name should be available `mockserver.<namespace>.svc.cluster.local`, i.e. `mockserver.mockserver.svc.cluster.local`

### Available Versions

Every released version is published to GHCR — the canonical, always-current list is the
[GHCR package page](https://github.com/orgs/mock-server/packages/container/package/charts%2Fmockserver)
(install any with `--version`). The legacy `.tgz` archives below are kept for the HTTP install method:

| Version | Chart Archive (legacy `.tgz`) |
|---------|---------------|
| 7.4.0 (latest) | [mockserver-7.4.0.tgz](https://www.mock-server.com/mockserver-7.4.0.tgz) |
| 6.1.0 | [mockserver-6.1.0.tgz](https://www.mock-server.com/mockserver-6.1.0.tgz) |
| 6.0.0 | [mockserver-6.0.0.tgz](https://www.mock-server.com/mockserver-6.0.0.tgz) |
| 5.15.0 | [mockserver-5.15.0.tgz](https://www.mock-server.com/mockserver-5.15.0.tgz) |
| 5.14.0 | [mockserver-5.14.0.tgz](https://www.mock-server.com/mockserver-5.14.0.tgz) |
| 5.13.2 | [mockserver-5.13.2.tgz](https://www.mock-server.com/mockserver-5.13.2.tgz) |
| 5.13.1 | [mockserver-5.13.1.tgz](https://www.mock-server.com/mockserver-5.13.1.tgz) |
| 5.13.0 | [mockserver-5.13.0.tgz](https://www.mock-server.com/mockserver-5.13.0.tgz) |
| 5.12.0 | [mockserver-5.12.0.tgz](https://www.mock-server.com/mockserver-5.12.0.tgz) |
| 5.11.2 | [mockserver-5.11.2.tgz](https://www.mock-server.com/mockserver-5.11.2.tgz) |
| 5.11.1 | [mockserver-5.11.1.tgz](https://www.mock-server.com/mockserver-5.11.1.tgz) |
| 5.11.0 | [mockserver-5.11.0.tgz](https://www.mock-server.com/mockserver-5.11.0.tgz) |

### Helm Delete

To completely remove the chart:

```bash
helm uninstall mockserver --namespace mockserver
```

> **Note:** if you enabled persistence, the chart-managed PersistentVolumeClaim is **not** removed by `helm uninstall` — delete it manually (`kubectl -n mockserver delete pvc -l release=mockserver`) if you want to discard the persisted data.
