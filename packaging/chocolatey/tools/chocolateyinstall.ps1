# Chocolatey install script for MockServer CLI.
#
# Downloads and unpacks the self-contained MockServer bundle (a zip that carries
# its own trimmed Java runtime — no separate JVM install required) and shims the
# `mockserver` launcher onto PATH.

$ErrorActionPreference = 'Stop'

$packageName = 'mockserver'
$version = $env:ChocolateyPackageVersion
$toolsDir = Split-Path -Parent $MyInvocation.MyCommand.Definition

# Only x64 Windows bundles are published today (build-all-bundles.sh targets
# windows/x86_64). MockServer requires a 64-bit OS.
if (-not [System.Environment]::Is64BitOperatingSystem) {
    throw "MockServer CLI requires a 64-bit version of Windows"
}

$url = "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-$version/mockserver-$version-windows-x86_64.zip"

$packageArgs = @{
    packageName    = $packageName
    unzipLocation  = $toolsDir
    url64bit       = $url
    checksum64     = '${SHA256_WINDOWS_X86_64}'
    checksumType64 = 'sha256'
}

# Download + verify checksum + extract the bundle under the package's tools dir.
Install-ChocolateyZipPackage @packageArgs

# The zip expands to a single top directory; shim its launcher as `mockserver`.
$launcher = Join-Path $toolsDir "mockserver-$version-windows-x86_64\bin\mockserver.bat"
Install-BinFile -Name 'mockserver' -Path $launcher
