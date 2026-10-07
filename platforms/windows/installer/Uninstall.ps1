#requires -Version 5.1
param([string]$InstallRoot="$env:ProgramFiles\RIMES",[switch]$AllowPendingRestart)
. "$PSScriptRoot\Package.Common.ps1"
Assert-Administrator
$state=Get-Content -LiteralPath "$InstallRoot\state.json" -Raw | ConvertFrom-Json
Assert-OwnedVersion $InstallRoot $state.active | Out-Null
Stop-OwnedBroker $state.active
$requiresSignOut=$false
try{Assert-Unlocked $state.active}catch{if(-not $AllowPendingRestart){throw};$requiresSignOut=$true}
$oldAutostart=Get-BrokerAutostart
$oldInstalledApp=Read-InstalledAppRegistration
$oldShortcut=Read-SettingsShortcut
try {
    foreach($arch in @('x86','x64')){Invoke-Registrar $state.active $arch 'unregister'}
    & "$($state.active)\x64\RimesBroker.exe" --remove-autostart
    if($LASTEXITCODE){throw 'Could not remove autostart'}
    foreach($arch in @('x86','x64')){Invoke-Registrar $state.active $arch 'verify-absent'}
    Restore-InstalledAppRegistration $null
    Remove-OwnedSettingsShortcut $InstallRoot
    Move-Item -LiteralPath "$InstallRoot\state.json" -Destination "$InstallRoot\uninstalled-state.json" -Force
} catch {
    $failure=$_
    $recoveryFailures=@()
    foreach($arch in @('x64','x86')){try{Invoke-Registrar $state.active $arch 'register'}catch{$recoveryFailures+=$_.ToString()}}
    try{Restore-BrokerAutostart $oldAutostart}catch{$recoveryFailures+=$_.ToString()}
    try{Restore-InstalledAppRegistration $oldInstalledApp}catch{$recoveryFailures+=$_.ToString()}
    try{Restore-SettingsShortcut $oldShortcut}catch{$recoveryFailures+=$_.ToString()}
    if($recoveryFailures.Count){throw "Uninstall failed: $failure. Recovery is incomplete: $($recoveryFailures -join '; '). Installed files and user data retained."}
    throw "Uninstall failed; registration, startup and Installed Apps entry restored. Installed files and user data retained. $failure"
}
Write-Host 'Unregistered RIMES. Version files, user dictionaries, settings and credentials retained for recovery. No other input method was changed.'
[pscustomobject]@{Uninstalled=$true;RequiresSignOut=$requiresSignOut;UserDataRetained=$true}
