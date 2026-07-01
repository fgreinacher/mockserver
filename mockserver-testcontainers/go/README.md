# MockServer Testcontainers Module for Go

A [Testcontainers](https://golang.testcontainers.org/) module that starts a
[MockServer](https://www.mock-server.com) Docker container for integration testing in Go.

## Installation

```bash
go get github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go
```

## Usage

```go
package example_test

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"strings"

	mockserver "github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go"
)

func Example() {
	ctx := context.Background()

	// Start a MockServer container
	ctr, err := mockserver.Run(ctx, mockserver.DefaultImage)
	if err != nil {
		log.Fatal(err)
	}
	defer ctr.Terminate(ctx)

	// Get the base URL
	url, err := ctr.URL(ctx)
	if err != nil {
		log.Fatal(err)
	}

	// Create an expectation
	expectation := `{
		"httpRequest": {"method": "GET", "path": "/hello"},
		"httpResponse": {"statusCode": 200, "body": "world"}
	}`
	req, _ := http.NewRequestWithContext(ctx, http.MethodPut, url+"/mockserver/expectation",
		strings.NewReader(expectation))
	req.Header.Set("Content-Type", "application/json")
	http.DefaultClient.Do(req)

	// Call the mocked endpoint
	resp, _ := http.Get(url + "/hello")
	fmt.Printf("Status: %d\n", resp.StatusCode)
	// Output: Status: 200
}
```

## API

### `Run(ctx, image, opts...) (*MockServerContainer, error)`

Creates and starts a MockServer container. Waits for the `/mockserver/status`
endpoint to respond with HTTP 200 before returning.

- `image` — Docker image (e.g. `"mockserver/mockserver:mockserver-7.3.0"`)
- `opts` — optional `testcontainers.ContainerCustomizer` values

### `MockServerContainer.URL(ctx) (string, error)`

Returns the HTTP base URL (e.g. `"http://localhost:32769"`).

### `MockServerContainer.ServerPort(ctx) (int, error)`

Returns the mapped host port for the MockServer container port 1080.

### Constants

- `DefaultPort` — `"1080/tcp"`
- `DefaultImage` — `"mockserver/mockserver:mockserver-7.3.0"`

## Build and Test

```bash
# Unit tests (no Docker required)
go test -run 'TestURL|TestDefault' ./...

# All tests (requires Docker)
go test -v ./...

# Vet
go vet ./...
```

## Requirements

- Go 1.22+
- Docker (for integration tests)
