# frozen_string_literal: true

# Integration tests that start a real MockServer Docker container via
# Testcontainers. These require a running Docker daemon and are skipped
# gracefully when Docker is unavailable (mirroring the Docker-gating used
# across the MockServer test suite).

require "spec_helper"
require "net/http"
require "uri"
require "json"

def docker_available?
  require "docker"
  Docker.version
  true
rescue StandardError
  false
end

RSpec.describe "MockServerContainer integration", :integration do
  before(:all) do
    skip "Docker is not available" unless docker_available?

    image = ENV.fetch("MOCKSERVER_IMAGE", Testcontainers::MockServerContainer.default_image)
    @container = Testcontainers::MockServerContainer.new(image).with_log_level("WARN")
    @container.start
  end

  after(:all) do
    @container&.stop(force: true)
  rescue StandardError
    # best-effort teardown
  end

  it "exposes a reachable HTTP endpoint" do
    uri = URI("#{@container.endpoint}/mockserver/status")
    response = Net::HTTP.start(uri.hostname, uri.port) do |http|
      http.request(Net::HTTP::Put.new(uri))
    end
    expect(response.code).to eq("200")
  end

  it "serves expectations created through the wired client" do
    client = @container.client
    client.when(
      MockServer::HttpRequest.request(path: "/hello")
    ).respond(
      MockServer::HttpResponse.response(status_code: 200, body: "world")
    )

    uri = URI("#{@container.endpoint}/hello")
    response = Net::HTTP.get_response(uri)
    expect(response.code).to eq("200")
    expect(response.body).to eq("world")

    client.verify(MockServer::HttpRequest.request(path: "/hello"), times: MockServer::VerificationTimes.at_least(1))
    client.reset
  end
end
