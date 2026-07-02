# frozen_string_literal: true

require "net/http"
require "uri"

require "testcontainers"
require "mockserver-client"

require_relative "mockserver/version"

module Testcontainers
  # A Testcontainers module for MockServer.
  #
  # Starts the official +mockserver/mockserver+ Docker image, waits for the
  # server to begin listening, and exposes connection helpers (host, mapped
  # port, HTTP/HTTPS endpoints) plus a ready-wired {#client}.
  #
  # MockServer serves HTTP, HTTPS, SOCKS, and HTTP CONNECT on a single unified
  # port (default +1080+).
  #
  # @example Basic usage
  #   container = Testcontainers::MockServerContainer.new.start
  #   client = container.client
  #   client.when(
  #     MockServer::HttpRequest.request(path: "/hello")
  #   ).respond(
  #     MockServer::HttpResponse.response(body: "world")
  #   )
  #   # point the system under test at container.endpoint
  #   container.stop
  #
  # @example Block form (auto-stop)
  #   Testcontainers::MockServerContainer.new.use do |container|
  #     client = container.client
  #     # ...
  #   end
  class MockServerContainer < ::Testcontainers::DockerContainer
    # Default MockServer port (HTTP, HTTPS, SOCKS, and HTTP CONNECT are all
    # served on a single unified port).
    MOCKSERVER_DEFAULT_PORT = 1080

    # The Docker image name on Docker Hub.
    IMAGE = "mockserver/mockserver"

    # Version of this gem (kept in lockstep with the MockServer release).
    VERSION = ::Testcontainers::Mockserver::VERSION

    # @param image [String, nil] full Docker image reference; when +nil+ the tag
    #   is derived from the MockServer client library version (see {.default_image}).
    # @param port [Integer] the port MockServer listens on inside the container
    def initialize(image = nil, port: MOCKSERVER_DEFAULT_PORT, **kwargs)
      super(image || self.class.default_image, **kwargs)
      @port = port
      @client = nil
      add_exposed_port(port)
      # Wait until MockServer answers PUT /mockserver/status with HTTP 200, the
      # same readiness signal used by the sibling polyglot modules. A host-side
      # TCP-port wait is unreliable here because the Docker port proxy accepts
      # connections before MockServer has bound inside the container.
      @wait_for = ->(container) { container.wait_until_ready }
      with_env("SERVER_PORT", port.to_s)
    end

    # Returns the mapped host port for MockServer.
    #
    # @return [Integer] the host port mapped to the MockServer container port
    def server_port
      mapped_port(@port)
    end

    # Blocks until MockServer answers PUT /mockserver/status with HTTP 200.
    # Used as the container's wait strategy.
    #
    # @param timeout [Integer] maximum seconds to wait
    # @param interval [Float] seconds between polls
    # @return [true] once MockServer is ready
    # @raise [Testcontainers::TimeoutError] if MockServer is not ready in time
    def wait_until_ready(timeout: 60, interval: 0.25)
      uri = URI("http://#{host}:#{server_port}/mockserver/status")
      deadline = Time.now + timeout
      loop do
        begin
          response = Net::HTTP.start(uri.hostname, uri.port, open_timeout: 2, read_timeout: 5) do |http|
            http.request(Net::HTTP::Put.new(uri))
          end
          return true if response.code == "200"
        rescue StandardError
          # MockServer is not accepting connections yet
        end
        raise ::Testcontainers::TimeoutError, "MockServer did not become ready within #{timeout}s" if Time.now > deadline

        sleep interval
      end
    end

    # @return [String] the HTTP endpoint in the form +http://host:port+
    def endpoint
      "http://#{host}:#{server_port}"
    end

    # MockServer serves HTTP and HTTPS on the same unified port.
    #
    # @return [String] the HTTPS endpoint in the form +https://host:port+
    def secure_endpoint
      "https://#{host}:#{server_port}"
    end

    # Returns a {MockServer::Client} connected to this container. The client is
    # created lazily on first call and cached; it is closed automatically when
    # the container is stopped via {#stop}.
    #
    # @return [MockServer::Client] a client connected to the running container
    def client
      @client ||= MockServer::Client.new(host, server_port)
    end

    # Stops the cached client (if any) and the container.
    #
    # @return [MockServerContainer] self
    def stop(force: false)
      if @client
        begin
          @client.close
        rescue StandardError # rubocop:disable Lint/SuppressedException
          # best-effort close; ignore errors while tearing down the client
        ensure
          @client = nil
        end
      end
      super
    end

    # Overrides the port MockServer listens on inside the container. Replaces
    # the exposed port so the TCP wait strategy targets the correct port.
    #
    # @param port [Integer]
    # @return [MockServerContainer] self
    def with_server_port(port)
      @port = port
      # Replace the exposed port (and its binding) rather than appending, so the
      # TCP wait strategy never blocks on a port MockServer is not listening on.
      @exposed_ports = nil
      @port_bindings = nil
      add_exposed_port(port)
      @wait_for = ->(container) { container.wait_until_ready }
      # Replace (not append) the SERVER_PORT env var — @env is an ordered list
      # and get_env returns the first match, so a stale entry would shadow this.
      @env&.reject! { |entry| entry.start_with?("SERVER_PORT=") }
      with_env("SERVER_PORT", port.to_s)
    end

    # Sets the MockServer log level (e.g. "INFO", "DEBUG", "WARN", "ERROR", "TRACE").
    #
    # @param level [String]
    # @return [MockServerContainer] self
    def with_log_level(level)
      with_env("MOCKSERVER_LOG_LEVEL", level)
    end

    # Sets a single MockServer property as an environment variable. The key must
    # be in MockServer env-var form (e.g. "MOCKSERVER_MAX_EXPECTATIONS").
    #
    # @param key [String]
    # @param value [String]
    # @return [MockServerContainer] self
    def with_property(key, value)
      with_env(key, value.to_s)
    end

    # Mounts an initialization JSON file into the container and configures
    # MockServer to load its expectations at startup.
    #
    # @param host_init_json_path [String] path on the host to the JSON file
    # @return [MockServerContainer] self
    def with_initialization_json(host_init_json_path)
      container_path = "/config/initializerJson.json"
      add_filesystem_bind(File.expand_path(host_init_json_path), container_path, "ro")
      with_env("MOCKSERVER_INITIALIZATION_JSON_PATH", container_path)
    end

    # Resolves the default Docker image, deriving the tag from the MockServer
    # client library version so the container image stays in lockstep with the
    # client. Falls back to +:latest+ when the version cannot be resolved.
    #
    # @return [String] e.g. "mockserver/mockserver:mockserver-7.3.0"
    def self.default_image
      tag = if defined?(MockServer::VERSION) && !MockServer::VERSION.to_s.empty?
              "mockserver-#{MockServer::VERSION}"
            else
              "latest"
            end
      "#{IMAGE}:#{tag}"
    end
  end
end
