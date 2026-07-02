# Publishing testcontainers-mockserver to RubyGems

## Prerequisites

- Ruby >= 3.0
- `gem` and `bundler` installed
- RubyGems API key stored in AWS Secrets Manager at `mockserver-build/rubygems`

## Build

```bash
cd mockserver-testcontainers/ruby
gem build testcontainers-mockserver.gemspec
```

This produces `testcontainers-mockserver-<version>.gem`.

## Publish (non-interactive)

```bash
# Retrieve the RubyGems API key from Secrets Manager
RUBYGEMS_API_KEY=$(aws secretsmanager get-secret-value \
  --profile mockserver-build \
  --secret-id mockserver-build/rubygems \
  --query SecretString --output text)

GEM_HOST_API_KEY="$RUBYGEMS_API_KEY" \
  gem push "testcontainers-mockserver-${RELEASE_VERSION}.gem"
```

## Liveness verification

```bash
gem install testcontainers-mockserver --version "${RELEASE_VERSION}"
ruby -e "require 'testcontainers/mockserver'; puts Testcontainers::Mockserver::VERSION"
```

Or via the RubyGems JSON API:

```bash
curl -sf "https://rubygems.org/api/v1/versions/testcontainers-mockserver.json" \
  | ruby -rjson -e "puts JSON.parse(STDIN.read).first['number']"
```
