# Chocolatey uninstall script for MockServer CLI.

$ErrorActionPreference = 'Stop'

# Remove the PATH shim. The extracted bundle under lib\mockserver\tools\ is
# removed by Chocolatey itself when the package is uninstalled.
Uninstall-BinFile -Name 'mockserver'
