# Publishing the Go Client

## Registry

The Go client is published to [pkg.go.dev](https://pkg.go.dev) via the Go module proxy.
pkg.go.dev indexes a module automatically the first time anyone fetches it after a git tag is pushed.

## Module Path

Current (monorepo): `github.com/mock-server/mockserver-monorepo/mockserver-client-go/v7`

Eventual split target: `github.com/mock-server/mockserver-client-go/v7`

### Semantic Import Versioning (the `/vN` suffix)

The module path carries a **major-version suffix** (`/v7`) as required by Go's
[Semantic Import Versioning](https://go.dev/ref/mod#major-version-suffixes) rule:
any module at major version 2 or higher must end its path in `/vN`. The client is
currently at major **v7**, so the path ends in `/v7`. When the next **major** is
released, bump the suffix to match — v8 becomes `.../mockserver-client-go/v8`,
v9 becomes `/v9`, and so on. Minor and patch releases (v7.x.y) do **not** change
the suffix.

The suffix lives only in the `module` line of `go.mod` (and therefore in every
import path); the module still lives in the `mockserver-client-go/` directory on
disk (there is no `v7/` subdirectory), and the release **tag** is unchanged
(`mockserver-client-go/v${VERSION}` — see below). Carrying the `/vN` suffix is
what lets other in-repo Go modules (e.g. `mockserver-testcontainers/go`) depend
on the published client via a normal `require`.

## Publish Command (non-interactive)

Go modules are published by pushing a git tag. For a subdirectory module in a monorepo, the tag
format is `<subdir>/v<VERSION>` (unaffected by the `/v7` module-path suffix — it stays
`mockserver-client-go/v${VERSION}`).

```bash
# Example for version 7.0.1:
VERSION="7.0.1"
git tag "mockserver-client-go/v${VERSION}"
git push origin "mockserver-client-go/v${VERSION}"
```

After the tag is pushed, pkg.go.dev will index the module within minutes. You can trigger
immediate indexing by fetching the module:

```bash
GOPROXY=https://proxy.golang.org GO111MODULE=on \
  go get "github.com/mock-server/mockserver-monorepo/mockserver-client-go/v7@v${VERSION}"
```

## Secret

None required. The Go module proxy indexes public repositories automatically.

## Verification

Check that the module page exists:

```bash
curl -sf "https://pkg.go.dev/github.com/mock-server/mockserver-monorepo/mockserver-client-go/v7@v${VERSION}" \
  | grep -q "mockserver-client-go" && echo "OK" || echo "NOT FOUND"
```

## Pre-publish Checklist

1. All tests pass: `cd mockserver-client-go && go test ./... && go vet ./...`
2. `go.mod` has the correct module path
3. No `replace` directives in `go.mod`
4. Version in tag matches the MockServer release version
