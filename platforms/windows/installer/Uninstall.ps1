#requires -Version 5.1
param([string]$InstallRoot="$env:ProgramFiles\RIMES",[switch]$AllowPendingRestart)
. "$PSScriptRoot\Package.Common.ps1"
Assert-Administrator
$statePath=[IO.Path]::GetFullPath($InstallRoot).TrimEnd('\')+'\state.json'
if(Test-Path -LiteralPath $statePath){
    $state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $active=$state.active
}else{
    # A missing state.json (manual cleanup, quarantine or an interrupted install) used to
    # abort here with an unhandled Get-Content error, before any unregistration ran. That
    # left the TSF text service registered and made RIMES impossible to remove. Recover the
    # newest version directory that still verifies against PACKAGE.json; ownership checks
    # are not relaxed.
    $active=Get-RimesActiveVersion $InstallRoot
    if(-not $active){throw "Cannot uninstall: $statePath is missing and no verified version directory was found under $InstallRoot\versions. Re-run Setup.ps1 from the matching package to repair the installation, then run Uninstall.ps1 again. No registration was changed."}
    Write-Warning "state.json is missing; using the newest verified version directory instead: $active"
}
Assert-OwnedVersion $InstallRoot $active | Out-Null
Stop-OwnedBroker $active
$requiresSignOut=$false
try{Assert-Unlocked $active}catch{if(-not $AllowPendingRestart){throw};$requiresSignOut=$true}
$oldAutostart=Get-BrokerAutostart
$oldInstalledApp=Read-InstalledAppRegistration
$oldShortcut=Read-SettingsShortcut
try {
    foreach($arch in @('x86','x64')){Invoke-Registrar $active $arch 'unregister'}
    & "$($active)\x64\RimesBroker.exe" --remove-autostart
    if($LASTEXITCODE){throw 'Could not remove autostart'}
    foreach($arch in @('x86','x64')){Invoke-Registrar $active $arch 'verify-absent'}
    Restore-InstalledAppRegistration $null
    Remove-OwnedSettingsShortcut $InstallRoot
    if(Test-Path -LiteralPath $statePath){Move-Item -LiteralPath $statePath -Destination "$InstallRoot\uninstalled-state.json" -Force}
} catch {
    $failure=$_
    $recoveryFailures=@()
    foreach($arch in @('x64','x86')){try{Invoke-Registrar $active $arch 'register'}catch{$recoveryFailures+=$_.ToString()}}
    try{Restore-BrokerAutostart $oldAutostart}catch{$recoveryFailures+=$_.ToString()}
    try{Restore-InstalledAppRegistration $oldInstalledApp}catch{$recoveryFailures+=$_.ToString()}
    try{Restore-SettingsShortcut $oldShortcut}catch{$recoveryFailures+=$_.ToString()}
    if($recoveryFailures.Count){throw "Uninstall failed: $failure. Recovery is incomplete: $($recoveryFailures -join '; '). Installed files and user data retained."}
    throw "Uninstall failed; registration, startup and Installed Apps entry restored. Installed files and user data retained. $failure"
}
Write-Host 'Unregistered RIMES. Version files, user dictionaries, settings and credentials retained for recovery. No other input method was changed.'
[pscustomobject]@{Uninstalled=$true;RequiresSignOut=$requiresSignOut;UserDataRetained=$true}
