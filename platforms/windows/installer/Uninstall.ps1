#requires -Version 5.1
param([string]$InstallRoot="$env:ProgramFiles\RIMES")
. "$PSScriptRoot\Package.Common.ps1"
Assert-Administrator
$statePath=[IO.Path]::GetFullPath($InstallRoot).TrimEnd('\')+'\state.json'
if(Test-Path -LiteralPath $statePath){
    $state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $active=$state.active
}else{
    # A missing state.json (manual cleanup, quarantine, interrupted install) used to
    # surface as an unhandled Get-Content error, which left the TSF registration in
    # place and made RIMES impossible to remove. Fall back to the newest verified
    # version directory, and otherwise fail with an actionable message.
    $active=Get-RimesActiveVersion $InstallRoot
    if(-not $active){throw "Cannot uninstall: $statePath is missing and no verified version directory was found under $InstallRoot\versions. Re-run Setup.ps1 from the matching package to repair the installation, then run Uninstall.ps1 again. No registration was changed."}
    Write-Warning "state.json is missing; using the newest verified version directory instead: $active"
}
Assert-OwnedVersion $InstallRoot $active | Out-Null
Stop-OwnedBroker $active
Assert-Unlocked $active
try {
    foreach($arch in @('x86','x64')){Invoke-Registrar $active $arch 'unregister'}
    & "$active\x64\RimesBroker.exe" --remove-autostart
    if($LASTEXITCODE){throw 'Could not remove autostart'}
    foreach($arch in @('x86','x64')){Invoke-Registrar $active $arch 'verify-absent'}
} catch {
    foreach($arch in @('x64','x86')){Invoke-Registrar $active $arch 'register'}
    throw 'Uninstall failed; registration restored. Installed files and user data retained.'
}
if(Test-Path -LiteralPath $statePath){Move-Item -LiteralPath $statePath -Destination "$InstallRoot\uninstalled-state.json" -Force}
Write-Output 'Unregistered RIMES. Version files, user dictionaries, settings and credentials retained for recovery. No other input method was changed.'
