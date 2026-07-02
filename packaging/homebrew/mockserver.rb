# Homebrew formula for the self-contained MockServer CLI bundle.
#
# Published to the mock-server/homebrew-tap tap by
# scripts/release/components/homebrew.sh. Install with:
#
#   brew install mock-server/tap/mockserver
#
# This tap formula installs the JVM-less bundle (each carries its own trimmed
# Java runtime, so no separate JDK is required). It is intentionally distinct
# from the JAR-based `mockserver` formula in Homebrew/homebrew-core (bumped by
# BrewTestBot from Maven Central), which requires an OpenJDK dependency.
#
# ${VERSION} and the four ${SHA256_*} placeholders are substituted with the
# release version and the real bundle checksums at publish time.
class Mockserver < Formula
  desc "Mock, proxy & record HTTP(S), gRPC, and async messaging for testing"
  homepage "https://www.mock-server.com"
  version "${VERSION}"
  license "Apache-2.0"

  on_macos do
    on_arm do
      url "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-${VERSION}/mockserver-${VERSION}-darwin-aarch64.tar.gz"
      sha256 "${SHA256_DARWIN_AARCH64}"
    end
    on_intel do
      url "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-${VERSION}/mockserver-${VERSION}-darwin-x86_64.tar.gz"
      sha256 "${SHA256_DARWIN_X86_64}"
    end
  end

  on_linux do
    on_arm do
      url "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-${VERSION}/mockserver-${VERSION}-linux-aarch64.tar.gz"
      sha256 "${SHA256_LINUX_AARCH64}"
    end
    on_intel do
      url "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-${VERSION}/mockserver-${VERSION}-linux-x86_64.tar.gz"
      sha256 "${SHA256_LINUX_X86_64}"
    end
  end

  def install
    # The archive expands to a single top directory that Homebrew has already
    # cd'd into; it holds bin/, lib/ and runtime/. Install the whole bundle
    # under libexec, then wrap its launcher so the runtime resolves correctly.
    libexec.install Dir["*"]
    (bin/"mockserver").write_env_script libexec/"bin/mockserver", {}
  end

  test do
    assert_match "MockServer", shell_output("#{bin}/mockserver version 2>&1")
  end
end
