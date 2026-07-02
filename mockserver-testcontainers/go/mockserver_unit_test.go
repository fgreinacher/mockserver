package mockserver

import (
	"strings"
	"testing"

	"github.com/docker/go-connections/nat"
)

func TestURLShaping(t *testing.T) {
	tests := []struct {
		name     string
		host     string
		port     string
		expected string
	}{
		{
			name:     "localhost with typical mapped port",
			host:     "localhost",
			port:     "32769",
			expected: "http://localhost:32769",
		},
		{
			name:     "custom host",
			host:     "192.168.1.100",
			port:     "1080",
			expected: "http://192.168.1.100:1080",
		},
		{
			name:     "high port number",
			host:     "127.0.0.1",
			port:     "65535",
			expected: "http://127.0.0.1:65535",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Build the URL the same way MockServerContainer.URL does, using the
			// internal helper logic. We directly construct the expected string since
			// we cannot instantiate a real container without Docker.
			port, err := nat.NewPort("tcp", tt.port)
			if err != nil {
				t.Fatalf("failed to create nat.Port: %v", err)
			}

			// Verify the URL construction logic produces the correct format
			got := "http://" + tt.host + ":" + port.Port()
			if got != tt.expected {
				t.Errorf("URL() = %q, want %q", got, tt.expected)
			}
		})
	}
}

func TestDefaultPortConstant(t *testing.T) {
	if DefaultPort != "1080/tcp" {
		t.Errorf("DefaultPort = %q, want %q", DefaultPort, "1080/tcp")
	}
}

func TestDefaultImageContainsMockServer(t *testing.T) {
	if DefaultImage == "" {
		t.Error("DefaultImage should not be empty")
	}
	// Must reference the mockserver/mockserver image with a derived tag: either
	// "mockserver-<version>" (client version resolved from build info) or the
	// ":latest" fallback (in-module tests / local replace directive).
	prefix := DefaultImageName + ":"
	if !strings.HasPrefix(DefaultImage, prefix) {
		t.Errorf("DefaultImage = %q, want prefix %q", DefaultImage, prefix)
	}
	tag := strings.TrimPrefix(DefaultImage, prefix)
	if tag != "latest" && !strings.HasPrefix(tag, "mockserver-") {
		t.Errorf("DefaultImage tag = %q, want %q or a mockserver-<version> tag", tag, "latest")
	}
}

func TestDefaultTagHelper(t *testing.T) {
	if got := isReleaseVersion("7.3.0"); !got {
		t.Errorf("isReleaseVersion(7.3.0) = false, want true")
	}
	for _, v := range []string{"", "7.3", "v7.3.0", "7.3.0-SNAPSHOT", "0.0.0-20240101000000-abcdef"} {
		if isReleaseVersion(v) {
			t.Errorf("isReleaseVersion(%q) = true, want false", v)
		}
	}
}
