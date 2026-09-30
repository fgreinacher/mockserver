// mockserver-healthcheck is the Docker HEALTHCHECK probe baked into every MockServer server image.
//
// It is a static binary rather than a JVM so the probe costs ~4 MiB instead of the ~30 MiB a
// second JVM needs inside the container's memory cgroup every interval. Behaviour mirrors
// org.mockserver.cli.HealthCheck: PUT http://localhost:<port>/mockserver/status, healthy (exit 0)
// only on HTTP 200, otherwise exit 1; 3s connect and 3s response timeouts; port from
// MOCKSERVER_SERVER_PORT, then SERVER_PORT (first entry of a comma-separated list), else 1080.
//
// The canonical source is docker/healthcheck/; each image build context carries a byte-identical
// copy (enforced by .buildkite/scripts/steps/docker-validate-sync.sh).
package main

import (
	"bufio"
	"net"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	defaultPort = 1080
	timeout     = 3 * time.Second
)

func main() {
	if check("localhost", resolvePort(os.Getenv)) {
		os.Exit(0)
	}
	os.Exit(1)
}

func resolvePort(getenv func(string) string) int {
	port := getenv("MOCKSERVER_SERVER_PORT")
	if port == "" {
		port = getenv("SERVER_PORT")
	}
	if port == "" {
		return defaultPort
	}
	if i := strings.IndexByte(port, ','); i >= 0 {
		port = port[:i]
	}
	parsed, err := strconv.ParseInt(strings.TrimSpace(port), 10, 32)
	if err != nil {
		return defaultPort
	}
	return int(parsed)
}

func check(host string, port int) bool {
	if port < 1 || port > 65535 {
		return false
	}
	address := net.JoinHostPort(host, strconv.Itoa(port))
	conn, err := net.DialTimeout("tcp", address, timeout)
	if err != nil {
		return false
	}
	defer conn.Close()
	if err := conn.SetDeadline(time.Now().Add(timeout)); err != nil {
		return false
	}
	request := "PUT /mockserver/status HTTP/1.1\r\nHost: " + address +
		"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
	if _, err := conn.Write([]byte(request)); err != nil {
		return false
	}
	statusLine, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil {
		return false
	}
	fields := strings.Fields(statusLine)
	return len(fields) >= 2 && strings.HasPrefix(fields[0], "HTTP/") && fields[1] == "200"
}
