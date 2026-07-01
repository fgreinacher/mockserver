# Publishing

## Registry

**pkg.go.dev** — Go modules are indexed automatically by the Go module proxy when
first fetched after a git tag is pushed.

## Module Path

```
github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go
```

> Eventual split target: `github.com/mock-server/mockserver-testcontainers-go`

## Publish Command (non-interactive)

```bash
# 1. Tag the module with the release version (must match the Go module path prefix)
git tag mockserver-testcontainers/go/v7.3.0

# 2. Push the tag to GitHub
git push origin mockserver-testcontainers/go/v7.3.0

# 3. Request the Go module proxy to index it (optional — happens on first `go get`)
GOPROXY=https://proxy.golang.org GO111MODULE=on \
  go list -m github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go@v7.3.0
```

## Secret Required

**None.** The Go module proxy (`proxy.golang.org`) indexes public modules
automatically on first fetch after a valid semver tag exists on the repository.

## Version Bumping

When releasing a new MockServer version, update the `DefaultImage` constant in
`mockserver.go` to reference the new tag:

```go
DefaultImage = "mockserver/mockserver:mockserver-X.Y.Z"
```

## Verification

After publishing, verify the module is indexed:

```bash
go list -m -versions github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go
```

Or visit: https://pkg.go.dev/github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go
