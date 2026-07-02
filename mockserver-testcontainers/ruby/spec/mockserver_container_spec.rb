# frozen_string_literal: true

# Unit tests for MockServerContainer configuration. These verify container
# configuration WITHOUT starting Docker — image derivation, environment
# variables, exposed ports, fluent helpers, and endpoint shaping.

require "spec_helper"

RSpec.describe Testcontainers::MockServerContainer do
  describe "default configuration" do
    subject(:container) { described_class.new }

    it "derives the default image tag from the MockServer client version" do
      expect(container.image).to eq("mockserver/mockserver:mockserver-#{MockServer::VERSION}")
    end

    it "exposes port 1080 by default" do
      expect(container.exposed_ports).to have_key("1080/tcp")
    end

    it "sets the SERVER_PORT env var to the default port" do
      expect(container.get_env("SERVER_PORT")).to eq("1080")
    end

    it "exposes the default port constant" do
      expect(described_class::MOCKSERVER_DEFAULT_PORT).to eq(1080)
    end

    it "accepts a custom image" do
      expect(described_class.new("mockserver/mockserver:latest").image)
        .to eq("mockserver/mockserver:latest")
    end

    it "accepts a custom port" do
      custom = described_class.new(port: 9090)
      expect(custom.exposed_ports).to have_key("9090/tcp")
      expect(custom.get_env("SERVER_PORT")).to eq("9090")
    end
  end

  describe ".default_image" do
    it "falls back to :latest when the client version is not resolvable" do
      allow(described_class).to receive(:default_image).and_call_original
      stub_const("MockServer::VERSION", "")
      expect(described_class.default_image).to eq("mockserver/mockserver:latest")
    end
  end

  describe "#with_server_port" do
    subject(:container) { described_class.new }

    it "returns self for chaining" do
      expect(container.with_server_port(9090)).to be(container)
    end

    it "replaces the default exposed port" do
      container.with_server_port(9090)
      expect(container.exposed_ports).to have_key("9090/tcp")
      expect(container.exposed_ports).not_to have_key("1080/tcp")
      expect(container.get_env("SERVER_PORT")).to eq("9090")
    end
  end

  describe "#with_log_level" do
    it "sets MOCKSERVER_LOG_LEVEL and returns self" do
      container = described_class.new
      expect(container.with_log_level("DEBUG")).to be(container)
      expect(container.get_env("MOCKSERVER_LOG_LEVEL")).to eq("DEBUG")
    end
  end

  describe "#with_property" do
    it "sets an arbitrary env var" do
      container = described_class.new.with_property("MOCKSERVER_MAX_EXPECTATIONS", "500")
      expect(container.get_env("MOCKSERVER_MAX_EXPECTATIONS")).to eq("500")
    end
  end

  describe "#with_initialization_json" do
    it "sets MOCKSERVER_INITIALIZATION_JSON_PATH" do
      container = described_class.new.with_initialization_json(__FILE__)
      expect(container.get_env("MOCKSERVER_INITIALIZATION_JSON_PATH"))
        .to eq("/config/initializerJson.json")
    end
  end

  describe "fluent chaining" do
    it "chains multiple helpers" do
      container = described_class.new
                                 .with_log_level("WARN")
                                 .with_server_port(8080)
                                 .with_property("MOCKSERVER_MAX_EXPECTATIONS", "100")
      expect(container.get_env("MOCKSERVER_LOG_LEVEL")).to eq("WARN")
      expect(container.get_env("SERVER_PORT")).to eq("8080")
      expect(container.get_env("MOCKSERVER_MAX_EXPECTATIONS")).to eq("100")
      expect(container.exposed_ports).to have_key("8080/tcp")
    end
  end

  describe "endpoint shaping" do
    subject(:container) { described_class.new }

    before do
      allow(container).to receive(:host).and_return("127.0.0.1")
      allow(container).to receive(:mapped_port).with(1080).and_return(49_152)
    end

    it "builds the HTTP endpoint" do
      expect(container.endpoint).to eq("http://127.0.0.1:49152")
    end

    it "builds the HTTPS endpoint on the same port" do
      expect(container.secure_endpoint).to eq("https://127.0.0.1:49152")
    end

    it "exposes the mapped server port" do
      expect(container.server_port).to eq(49_152)
    end
  end

  describe "#client" do
    subject(:container) { described_class.new }

    before do
      allow(container).to receive(:host).and_return("localhost")
      allow(container).to receive(:mapped_port).with(1080).and_return(51_000)
    end

    it "returns a MockServer client pointed at the mapped host/port" do
      expect(MockServer::Client).to receive(:new).with("localhost", 51_000).and_return(:client)
      expect(container.client).to eq(:client)
    end

    it "caches the client across calls" do
      allow(MockServer::Client).to receive(:new).and_return(Object.new)
      expect(container.client).to be(container.client)
    end
  end
end
