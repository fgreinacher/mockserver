package main

import (
	"bufio"
	"net"
	"strings"
	"testing"
	"time"
)

func env(values map[string]string) func(string) string {
	return func(key string) string { return values[key] }
}

func TestResolvePortMatchesJavaHealthCheck(t *testing.T) {
	cases := []struct {
		name string
		env  map[string]string
		want int
	}{
		{"default when unset", map[string]string{}, 1080},
		{"long name", map[string]string{"MOCKSERVER_SERVER_PORT": "1090"}, 1090},
		{"short name", map[string]string{"SERVER_PORT": "1091"}, 1091},
		{"long name wins", map[string]string{"MOCKSERVER_SERVER_PORT": "1092", "SERVER_PORT": "1093"}, 1092},
		{"empty long name falls through", map[string]string{"MOCKSERVER_SERVER_PORT": "", "SERVER_PORT": "1094"}, 1094},
		{"first of a list", map[string]string{"SERVER_PORT": "1095,1096"}, 1095},
		{"first of a list trimmed", map[string]string{"SERVER_PORT": " 1097 , 1098"}, 1097},
		{"whitespace only", map[string]string{"SERVER_PORT": "  "}, 1080},
		{"not a number", map[string]string{"SERVER_PORT": "abc"}, 1080},
		{"int overflow", map[string]string{"SERVER_PORT": "99999999999"}, 1080},
		{"plus sign accepted", map[string]string{"SERVER_PORT": "+1099"}, 1099},
		{"out of range is kept and later fails", map[string]string{"SERVER_PORT": "70000"}, 70000},
	}
	for _, c := range cases {
		if got := resolvePort(env(c.env)); got != c.want {
			t.Errorf("%s: resolvePort = %d, want %d", c.name, got, c.want)
		}
	}
}

// serve answers exactly one connection with the given raw response, recording the request line.
func serve(t *testing.T, response string, delay time.Duration) (int, <-chan string) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	requestLine := make(chan string, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		line, _ := bufio.NewReader(conn).ReadString('\n')
		requestLine <- strings.TrimSpace(line)
		time.Sleep(delay)
		conn.Write([]byte(response))
	}()
	return listener.Addr().(*net.TCPAddr).Port, requestLine
}

func TestCheckHealthyOn200(t *testing.T) {
	port, requestLine := serve(t, "HTTP/1.1 200 OK\r\ncontent-length: 0\r\n\r\n", 0)
	if !check("127.0.0.1", port) {
		t.Fatal("expected healthy on 200")
	}
	if got := <-requestLine; got != "PUT /mockserver/status HTTP/1.1" {
		t.Fatalf("request line = %q", got)
	}
}

func TestCheckUnhealthyOnNon200(t *testing.T) {
	for _, status := range []string{"401 Unauthorized", "404 Not Found", "500 Internal Server Error", "2000 Bogus"} {
		port, _ := serve(t, "HTTP/1.1 "+status+"\r\ncontent-length: 0\r\n\r\n", 0)
		if check("127.0.0.1", port) {
			t.Fatalf("expected unhealthy on %s", status)
		}
	}
}

func TestCheckUnhealthyOnGarbageOrEmptyResponse(t *testing.T) {
	for _, response := range []string{"", "\x16\x03\x01garbage\n", "200\n"} {
		port, _ := serve(t, response, 0)
		if check("127.0.0.1", port) {
			t.Fatalf("expected unhealthy on %q", response)
		}
	}
}

func TestCheckUnhealthyWhenNothingListening(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	if check("127.0.0.1", port) {
		t.Fatal("expected unhealthy with no listener")
	}
}

func TestCheckUnhealthyOnInvalidPort(t *testing.T) {
	for _, port := range []int{0, -1, 70000} {
		if check("127.0.0.1", port) {
			t.Fatalf("expected unhealthy for port %d", port)
		}
	}
}

func TestCheckTimesOutOnSlowResponse(t *testing.T) {
	port, _ := serve(t, "HTTP/1.1 200 OK\r\n\r\n", timeout+time.Second)
	start := time.Now()
	if check("127.0.0.1", port) {
		t.Fatal("expected unhealthy when the response exceeds the timeout")
	}
	if elapsed := time.Since(start); elapsed > timeout+500*time.Millisecond {
		t.Fatalf("check took %v, expected about %v", elapsed, timeout)
	}
}
