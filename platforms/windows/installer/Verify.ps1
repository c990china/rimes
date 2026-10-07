#requires -Version 5.1
param([string]$InstallRoot="$env:ProgramFiles\RIMES")
. "$PSScriptRoot\Package.Common.ps1"
$statePath=[IO.Path]::GetFullPath($InstallRoot).TrimEnd('\')+'\state.json'
if(Test-Path -LiteralPath $statePath){
    $state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $active=$state.active
    $requiresSignOut=[bool]$state.requiresSignOut
}else{
    # Same recovery path as Uninstall.ps1: verification must not depend on a
    # state.json that may be gone. requiresSignOut is unknown without it.
    $active=Get-RimesActiveVersion $InstallRoot
    if(-not $active){throw "Cannot verify: $statePath is missing and no verified version directory was found under $InstallRoot\versions."}
    $requiresSignOut=$false
    Write-Warning "state.json is missing; verifying the newest verified version directory instead: $active"
}
$manifest=Assert-OwnedVersion $InstallRoot $active
foreach($arch in @('x64','x86')){Invoke-Registrar $active $arch 'verify' | Out-Host}
& "$active\x64\RimesBroker.exe" --print-paths | Out-Host
if($LASTEXITCODE){throw 'Broker dependency check failed'}
[pscustomobject]@{Verified=$true;Version=$manifest.version;Commit=$manifest.commit;Directory=$active;UserData="$env:APPDATA\RIMES";RequiresSignOut=$requiresSignOut;HostInputAcceptance='Requires desktop testing'}
