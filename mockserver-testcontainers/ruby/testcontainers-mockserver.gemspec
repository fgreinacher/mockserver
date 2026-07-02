# frozen_string_literal: true

lib = File.expand_path("lib", __dir__)
$LOAD_PATH.unshift(lib) unless $LOAD_PATH.include?(lib)
require "testcontainers/mockserver/version"

Gem::Specification.new do |spec|
  spec.name          = "testcontainers-mockserver"
  spec.version       = Testcontainers::Mockserver::VERSION
  spec.authors       = ["James Bloom"]
  spec.email         = ["jamesdbloom@gmail.com"]
  spec.summary       = "Testcontainers module for MockServer"
  spec.description   = "Testcontainers module for MockServer — starts a mockserver/mockserver " \
                       "Docker container, waits for readiness, and exposes connection helpers " \
                       "plus a ready-wired MockServer client."
  spec.homepage      = "https://www.mock-server.com"
  spec.license       = "Apache-2.0"

  spec.required_ruby_version = ">= 3.0"

  spec.metadata = {
    "source_code_uri"   => "https://github.com/mock-server/mockserver-monorepo",
    "changelog_uri"     => "https://www.mock-server.com/mock_server/changelog.html",
    "bug_tracker_uri"   => "https://github.com/mock-server/mockserver-monorepo/issues",
    "documentation_uri" => "https://www.mock-server.com/mock_server/mockserver_testcontainers.html",
  }

  spec.files         = Dir["lib/**/*.rb"] + %w[README.md Gemfile testcontainers-mockserver.gemspec]
  spec.require_paths = ["lib"]

  spec.add_dependency "mockserver-client", ">= 5.15"
  spec.add_dependency "testcontainers-core", "~> 0.2"

  spec.add_development_dependency "rspec", "~> 3.12"
  spec.add_development_dependency "rspec_junit_formatter", "~> 0.6"
end
