#requires -Version 5.1
param([string]$InstallRoot="$env:ProgramFiles\RIMES")
. "$PSScriptRoot\Package.Common.ps1"
$state=Get-Content -LiteralPath "$InstallRoot\state.json" -Raw | ConvertFrom-Json
$manifest=Assert-OwnedVersion $InstallRoot $state.active
Assert-InstalledAppRegistration $InstallRoot $state.active $manifest
Assert-SettingsShortcut $InstallRoot $state.active
foreach($arch in @('x64','x86')){Invoke-Registrar $state.active $arch 'verify' | Out-Host}
& "$($state.active)\x64\RimesBroker.exe" --print-paths | Out-Host
if($LASTEXITCODE){throw 'Broker dependency check failed'}
[pscustomobject]@{Verified=$true;Version=$manifest.version;Commit=$manifest.commit;Directory=$state.active;UserData="$env:APPDATA\RIMES";RequiresSignOut=$state.requiresSignOut;HostInputAcceptance='Requires desktop testing'}
