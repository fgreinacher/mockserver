//! # testcontainers-mockserver
//!
//! A [Testcontainers](https://crates.io/crates/testcontainers) module for
//! [MockServer](https://www.mock-server.com) — the open-source HTTP(S) mock server and proxy.
//!
//! This crate provides a [`MockServer`] image type that implements the `testcontainers::Image`
//! trait. It starts the `mockserver/mockserver` Docker image, waits for readiness, and exposes
//! helpers to retrieve the mapped port and base URL.
//!
//! ## Quick Start (sync)
//!
//! ```rust,no_run
//! use testcontainers::runners::SyncRunner;
//! use testcontainers_mockserver::MockServer;
//!
//! let container = MockServer::default().start().unwrap();
//! let base_url = testcontainers_mockserver::base_url(&container);
//! // base_url is e.g. "http://localhost:32789"
//! ```
//!
//! ## Async
//!
//! ```rust,no_run
//! use testcontainers::runners::AsyncRunner;
//! use testcontainers_mockserver::MockServer;
//!
//! # async fn example() {
//! let container = MockServer::default().start().await.unwrap();
//! let base_url = testcontainers_mockserver::async_base_url(&container).await;
//! // Use base_url with your HTTP client
//! # }
//! ```

use std::borrow::Cow;

use testcontainers::core::ports::ContainerPort;
use testcontainers::core::WaitFor;
use testcontainers::Image;

/// The default MockServer version, derived from this crate's own version
/// (`CARGO_PKG_VERSION`) so the default image tag stays in lockstep with the
/// crate release without a hand-maintained constant.
pub const MOCKSERVER_VERSION: &str = env!("CARGO_PKG_VERSION");

/// The Docker image name on Docker Hub.
const IMAGE_NAME: &str = "mockserver/mockserver";

/// The default port MockServer listens on (HTTP, HTTPS, SOCKS, HTTP CONNECT — all unified).
pub const DEFAULT_PORT: u16 = 1080;

/// The log message MockServer emits when it is ready to accept connections.
///
/// Deliberately WITHOUT the trailing colon. `LifeCycle.startedServer()` writes
/// `started on port: 1080` for a single port but `started on ports: [1080, 1090]`
/// for several, so matching `started on port:` silently never fires whenever more
/// than one port is configured and the container wait then blocks until it times
/// out. `started on port` is the stable prefix common to both forms.
///
/// The other Testcontainers modules in this repository avoid the problem entirely by waiting on
/// `PUT /mockserver/status` instead; a log wait is used here because the Rust testcontainers crate
/// has no built-in HTTP wait strategy.
const READY_LOG_MESSAGE: &str = "started on port";

/// A Testcontainers [`Image`] for [MockServer](https://www.mock-server.com).
///
/// Starts `mockserver/mockserver:<tag>` with port 1080 exposed, waiting for the
/// "started on port" log message that indicates readiness (matched without the
/// trailing colon so it also fires for the multi-port `started on ports: [...]`
/// form).
///
/// # Configuration
///
/// Use the builder methods to customize the image tag and environment variables:
///
/// ```rust
/// use testcontainers_mockserver::MockServer;
///
/// let image = MockServer::new("mockserver-7.0.0")
///     .with_env("MOCKSERVER_LOG_LEVEL", "DEBUG")
///     .with_env("MOCKSERVER_MAX_EXPECTATIONS", "500");
/// ```
#[derive(Debug, Clone)]
pub struct MockServer {
    tag: String,
    env_vars: Vec<(String, String)>,
    exposed_ports: Vec<ContainerPort>,
}

impl MockServer {
    /// Creates a new `MockServer` image with the given Docker image tag.
    ///
    /// The tag typically follows the pattern `mockserver-<version>` (e.g. `mockserver-7.0.0`).
    pub fn new(tag: impl Into<String>) -> Self {
        Self {
            tag: tag.into(),
            env_vars: vec![("SERVER_PORT".to_string(), DEFAULT_PORT.to_string())],
            exposed_ports: vec![ContainerPort::Tcp(DEFAULT_PORT)],
        }
    }

    /// Sets a MockServer environment variable on the container.
    ///
    /// Common variables include:
    /// - `MOCKSERVER_LOG_LEVEL` (TRACE, DEBUG, INFO, WARN, ERROR)
    /// - `MOCKSERVER_MAX_EXPECTATIONS`
    /// - `MOCKSERVER_INITIALIZATION_JSON_PATH`
    /// - `SERVER_PORT`
    pub fn with_env(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.env_vars.push((key.into(), value.into()));
        self
    }

    /// Overrides the server port (default 1080).
    ///
    /// This updates the `SERVER_PORT` env var and the exposed port list.
    pub fn with_server_port(mut self, port: u16) -> Self {
        // Remove old SERVER_PORT entry and replace the exposed port
        self.env_vars.retain(|(k, _)| k != "SERVER_PORT");
        self.env_vars
            .push(("SERVER_PORT".to_string(), port.to_string()));
        self.exposed_ports = vec![ContainerPort::Tcp(port)];
        self
    }
}

impl Default for MockServer {
    /// Creates a `MockServer` image with the default tag `mockserver-<MOCKSERVER_VERSION>`.
    fn default() -> Self {
        Self::new(format!("mockserver-{MOCKSERVER_VERSION}"))
    }
}

impl Image for MockServer {
    fn name(&self) -> &str {
        IMAGE_NAME
    }

    fn tag(&self) -> &str {
        &self.tag
    }

    fn ready_conditions(&self) -> Vec<WaitFor> {
        vec![WaitFor::message_on_stdout(READY_LOG_MESSAGE)]
    }

    fn env_vars(
        &self,
    ) -> impl IntoIterator<Item = (impl Into<Cow<'_, str>>, impl Into<Cow<'_, str>>)> {
        self.env_vars
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_str()))
    }

    fn expose_ports(&self) -> &[ContainerPort] {
        &self.exposed_ports
    }
}

/// Returns the HTTP base URL for a running MockServer container (sync).
///
/// The returned URL has the form `http://localhost:<mapped_port>` and can be used directly
/// with any HTTP client to interact with the MockServer control plane.
///
/// # Example
///
/// ```rust,no_run
/// use testcontainers::runners::SyncRunner;
/// use testcontainers_mockserver::MockServer;
///
/// let container = MockServer::default().start().unwrap();
/// let url = testcontainers_mockserver::base_url(&container);
/// ```
pub fn base_url(container: &testcontainers::Container<MockServer>) -> String {
    let host = container
        .get_host()
        .expect("MockServer container host should be resolvable");
    let port = container
        .get_host_port_ipv4(DEFAULT_PORT)
        .expect("MockServer port 1080 should be mapped");
    format!("http://{host}:{port}")
}

/// Returns the HTTP base URL for a running MockServer container (async).
///
/// The returned URL has the form `http://<host>:<mapped_port>` and can be used directly
/// with any HTTP client to interact with the MockServer control plane.
///
/// # Example
///
/// ```rust,no_run
/// use testcontainers::runners::AsyncRunner;
/// use testcontainers_mockserver::MockServer;
///
/// # async fn example() {
/// let container = MockServer::default().start().await.unwrap();
/// let url = testcontainers_mockserver::async_base_url(&container).await;
/// # }
/// ```
pub async fn async_base_url(container: &testcontainers::ContainerAsync<MockServer>) -> String {
    let host = container.get_host().await.unwrap();
    let port = container.get_host_port_ipv4(DEFAULT_PORT).await.unwrap();
    format!("http://{host}:{port}")
}

/// Returns a [`MockServerClient`](mockserver_client::MockServerClient) connected to a running
/// MockServer container (sync). Mirrors the Java module's `getClient()`.
///
/// # Example
///
/// ```rust,no_run
/// use testcontainers::runners::SyncRunner;
/// use testcontainers_mockserver::MockServer;
///
/// let container = MockServer::default().start().unwrap();
/// let client = testcontainers_mockserver::client(&container).unwrap();
/// // client.when(...).respond(...);
/// ```
pub fn client(
    container: &testcontainers::Container<MockServer>,
) -> mockserver_client::Result<mockserver_client::MockServerClient> {
    let host = container
        .get_host()
        .expect("MockServer container host should be resolvable");
    let port = container
        .get_host_port_ipv4(DEFAULT_PORT)
        .expect("MockServer port 1080 should be mapped");
    mockserver_client::ClientBuilder::new(host.to_string(), port).build()
}

/// Returns a [`MockServerClient`](mockserver_client::MockServerClient) connected to a running
/// MockServer container (async). The client itself is synchronous; only the host/port
/// resolution is awaited.
///
/// # Example
///
/// ```rust,no_run
/// use testcontainers::runners::AsyncRunner;
/// use testcontainers_mockserver::MockServer;
///
/// # async fn example() {
/// let container = MockServer::default().start().await.unwrap();
/// let client = testcontainers_mockserver::async_client(&container).await.unwrap();
/// # }
/// ```
pub async fn async_client(
    container: &testcontainers::ContainerAsync<MockServer>,
) -> mockserver_client::Result<mockserver_client::MockServerClient> {
    let host = container.get_host().await.unwrap();
    let port = container.get_host_port_ipv4(DEFAULT_PORT).await.unwrap();
    mockserver_client::ClientBuilder::new(host.to_string(), port).build()
}

#[cfg(test)]
mod tests {
    use super::*;
    use testcontainers::core::WaitFor;

    #[test]
    fn default_image_name() {
        let image = MockServer::default();
        assert_eq!(image.name(), "mockserver/mockserver");
    }

    #[test]
    fn default_image_tag() {
        let image = MockServer::default();
        assert_eq!(image.tag(), format!("mockserver-{MOCKSERVER_VERSION}"));
    }

    #[test]
    fn custom_tag() {
        let image = MockServer::new("mockserver-5.15.0");
        assert_eq!(image.tag(), "mockserver-5.15.0");
    }

    #[test]
    fn default_exposed_port_is_1080_tcp() {
        let image = MockServer::default();
        assert_eq!(image.expose_ports(), &[ContainerPort::Tcp(1080)]);
    }

    #[test]
    fn custom_server_port_replaces_default() {
        let image = MockServer::default().with_server_port(9090);
        assert_eq!(image.expose_ports(), &[ContainerPort::Tcp(9090)]);

        // Verify SERVER_PORT env is updated
        let server_port_entry = image
            .env_vars
            .iter()
            .find(|(k, _)| k == "SERVER_PORT");
        assert_eq!(server_port_entry.unwrap().1, "9090");
    }

    #[test]
    fn with_env_adds_variable() {
        let image = MockServer::default().with_env("MOCKSERVER_LOG_LEVEL", "DEBUG");
        let has_log_level = image
            .env_vars
            .iter()
            .any(|(k, v)| k == "MOCKSERVER_LOG_LEVEL" && v == "DEBUG");
        assert!(has_log_level);
    }

    /// The readiness message must be the stable prefix shared by BOTH banner forms
    /// MockServer emits: `started on port: 1080` (one port) and
    /// `started on ports: [1080, 1090]` (several). Matching the singular form with
    /// its colon never fires in the multi-port case, so the container wait blocks
    /// until it times out.
    #[test]
    fn ready_log_message_matches_single_and_multi_port_banners() {
        let single = "2026-08-21 22:14:22 7.6.0 INFO 1080 started on port: 1080 ";
        let multi = "2026-08-21 22:14:24 7.6.0 INFO 1080 started on ports: [1080, 1090] ";
        assert!(
            single.contains(READY_LOG_MESSAGE),
            "readiness message {READY_LOG_MESSAGE:?} does not match the single-port banner"
        );
        assert!(
            multi.contains(READY_LOG_MESSAGE),
            "readiness message {READY_LOG_MESSAGE:?} does not match the multi-port banner"
        );
        assert!(
            !single.contains("started on ports"),
            "single-port banner sample should not contain the plural form"
        );
    }

    #[test]
    fn ready_conditions_include_log_wait() {
        let image = MockServer::default();
        let conditions = image.ready_conditions();
        assert_eq!(conditions.len(), 1);
        match &conditions[0] {
            WaitFor::Log(_) => {} // expected
            other => panic!("Expected WaitFor::Log, got {:?}", other),
        }
    }

    #[test]
    fn env_vars_include_server_port_by_default() {
        let image = MockServer::default();
        let has_port = image
            .env_vars
            .iter()
            .any(|(k, v)| k == "SERVER_PORT" && v == "1080");
        assert!(has_port);
    }

    #[test]
    fn builder_chaining() {
        let image = MockServer::new("latest")
            .with_env("MOCKSERVER_LOG_LEVEL", "WARN")
            .with_env("MOCKSERVER_MAX_EXPECTATIONS", "100")
            .with_server_port(8080);

        assert_eq!(image.tag(), "latest");
        assert_eq!(image.expose_ports(), &[ContainerPort::Tcp(8080)]);
        assert!(image
            .env_vars
            .iter()
            .any(|(k, v)| k == "MOCKSERVER_LOG_LEVEL" && v == "WARN"));
        assert!(image
            .env_vars
            .iter()
            .any(|(k, v)| k == "MOCKSERVER_MAX_EXPECTATIONS" && v == "100"));
    }

    /// Integration test that starts a real MockServer container.
    /// Ignored by default — run with `cargo test -- --ignored` when Docker is available.
    #[tokio::test]
    #[ignore]
    async fn start_mockserver_container() {
        use testcontainers::runners::AsyncRunner;

        let container = MockServer::default()
            .start()
            .await
            .expect("Failed to start MockServer container");

        let url = async_base_url(&container).await;

        // Verify the container is responsive via the status endpoint
        let client = reqwest::Client::new();
        let resp = client
            .put(format!("{url}/mockserver/status"))
            .send()
            .await
            .expect("Failed to reach MockServer status endpoint");

        let status = resp.status().as_u16();
        // Emit a stable, intentional evidence marker. The CI step
        // (rust-testcontainers-test.sh) greps for this line to fail closed:
        // it proves a real container started and answered /mockserver/status,
        // rather than trusting cargo's exit code (an #[ignore]d or filtered-out
        // test would still let `cargo test` exit 0 having started nothing).
        println!("RUST_TESTCONTAINERS_STATUS_OK url={url} status={status}");
        assert_eq!(status, 200);

        // Verify we can create an expectation
        let expectation_json = r#"[{
            "httpRequest": { "method": "GET", "path": "/hello" },
            "httpResponse": { "statusCode": 200, "body": "world" }
        }]"#;
        let resp = client
            .put(format!("{url}/mockserver/expectation"))
            .header("Content-Type", "application/json")
            .body(expectation_json)
            .send()
            .await
            .expect("Failed to create expectation");
        assert_eq!(resp.status().as_u16(), 201);

        // Verify the mocked endpoint responds
        let resp = client
            .get(format!("{url}/hello"))
            .send()
            .await
            .expect("Failed to call mocked endpoint");
        assert_eq!(resp.status().as_u16(), 200);
        let body = resp.text().await.unwrap();
        assert_eq!(body, "world");
    }

    #[test]
    #[ignore = "requires a running Docker daemon"]
    fn client_helper_connects_to_container() {
        use testcontainers::runners::SyncRunner;

        let container = MockServer::default()
            .start()
            .expect("Failed to start MockServer container");

        // The client() helper returns a MockServerClient wired to the mapped
        // host/port; a control-plane reset proves it reaches the container.
        let client = client(&container).expect("client() should build a connected client");
        client.reset().expect("reset should succeed against the running container");
    }
}
