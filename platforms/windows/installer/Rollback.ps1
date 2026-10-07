#requires -Version 5.1
param([string]$InstallRoot="$env:ProgramFiles\RIMES")
. "$PSScriptRoot\Package.Common.ps1"
Assert-Administrator
$state=Get-Content -LiteralPath "$InstallRoot\state.json" -Raw | ConvertFrom-Json
if(-not $state.previous){ & "$PSScriptRoot\Restore-Legacy.ps1" -InstallRoot $InstallRoot; return }
Assert-OwnedVersion $InstallRoot $state.previous | Out-Null
& "$PSScriptRoot\Install.ps1" -InstallRoot $InstallRoot -PackageDirectory $state.previous
