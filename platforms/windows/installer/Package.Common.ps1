#requires -Version 5.1
Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'
function Read-VerifiedPackage([string]$Directory) {
    $root = [IO.Path]::GetFullPath($Directory).TrimEnd('\')
    $manifest = Get-Content -LiteralPath "$root\PACKAGE.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.formatVersion -ne 1 -or $manifest.product -ne 'RIMES' -or $manifest.protocol -ne 2 -or $manifest.commit -notmatch '^[a-f0-9]{40}$' -or $manifest.version -notmatch '^[a-zA-Z0-9._-]{1,64}$') { throw 'Unsupported package manifest' }
    $seen = @{}
    foreach ($file in $manifest.files) {
        if ($file.path -match '(^[/\\]|:|(^|[/\\])\.\.([/\\]|$))') { throw 'Invalid package path' }
        $path = [IO.Path]::GetFullPath((Join-Path $root $file.path))
        if (-not $path.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase) -or $seen.ContainsKey($path)) { throw 'Invalid or duplicate package path' }
        $seen[$path] = $true
        $cursor = $path
        while ($cursor.Length -ge $root.Length) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse points are not accepted' }
            $cursor = Split-Path -Parent $cursor
        }
        $item = Get-Item -LiteralPath $path
        if ($item.PSIsContainer -or $item.Length -ne $file.bytes -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $file.sha256) { throw "Package checksum failed: $($file.path)" }
    }
    foreach ($item in Get-ChildItem -LiteralPath $root -Recurse -File) {
        if ($item.FullName -ne "$root\PACKAGE.json" -and -not $seen.ContainsKey($item.FullName)) { throw "Unlisted package file: $($item.Name)" }
    }
    return $manifest
}
function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not ([Security.Principal.WindowsPrincipal]$identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run this script from an elevated 64-bit PowerShell window.' }
    if (-not [Environment]::Is64BitOperatingSystem -or -not [Environment]::Is64BitProcess) { throw 'Windows x64 and 64-bit PowerShell are required.' }
}
function Invoke-Registrar([string]$Directory,[string]$Architecture,[string]$Operation) {
    $registrar = Join-Path $Directory "$Architecture\RimesRegistrar.exe"
    & $registrar $Operation --dll (Join-Path $Directory "$Architecture\RimesTsf.dll")
    if ($LASTEXITCODE -ne 0) { throw "Registrar $Operation $Architecture failed: $LASTEXITCODE" }
}
function Assert-Unlocked([string]$Directory) {
    foreach ($arch in @('x64','x86')) {
        $path = Join-Path $Directory "$arch\RimesTsf.dll"
        try { $stream = [IO.File]::Open($path,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None); $stream.Dispose() }
        catch { throw "RIMES is loaded by an application. Switch input methods, close those applications or sign out, then retry. No files were overwritten: $path" }
    }
}
function Stop-OwnedBroker([string]$Directory) {
    foreach ($process in Get-Process -Name RimesBroker -ErrorAction SilentlyContinue) {
        if ($process.Path -eq (Join-Path $Directory 'x64\RimesBroker.exe')) {
            # A pending process-local Buffer is never silently discarded by upgrade.
            throw 'Exit RIMES from its tray after copying or sending Buffer content, then retry the installation.'
        }
    }
}
function Write-InstallState([string]$Root,$State) {
    $temporary = Join-Path $Root ('state.'+[guid]::NewGuid().ToString('N')+'.tmp')
    $State | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $temporary -Encoding UTF8
    Move-Item -LiteralPath $temporary -Destination (Join-Path $Root 'state.json') -Force
}
function Assert-OwnedVersion([string]$Root,[string]$Directory) {
    $versions=[IO.Path]::GetFullPath((Join-Path $Root 'versions')).TrimEnd('\')+'\'
    if (-not [IO.Path]::GetFullPath($Directory).StartsWith($versions,[StringComparison]::OrdinalIgnoreCase)) {throw 'Installed state points outside the managed versions directory'}
    return Read-VerifiedPackage $Directory
}
function Get-LegacyViews {
    $entries=@()
    foreach($arch in @('x64','x86')) {
        $view=if($arch -eq 'x64'){[Microsoft.Win32.RegistryView]::Registry64}else{[Microsoft.Win32.RegistryView]::Registry32}
        $base=[Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine,$view)
        $key=$base.OpenSubKey('SOFTWARE\Classes\CLSID\{0B2C570B-9811-45DF-989B-EA306281F6B4}\InprocServer32')
        if($key){
            $path=[string]$key.GetValue('')
            if(-not (Test-Path -LiteralPath $path -PathType Leaf)){throw 'Existing RIMES registration points to a missing file; repair it before migrating.'}
            $entries += [pscustomobject]@{architecture=$arch;dll=$path;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
            $key.Dispose()
        }
        $base.Dispose()
    }
    return $entries
}
function Invoke-LegacyRegistrar([string]$Package,$Entry,[string]$Operation){
    if((Get-FileHash -LiteralPath $Entry.dll -Algorithm SHA256).Hash -ne $Entry.sha256){throw 'Previous DLL changed since its recovery record was written'}
    & (Join-Path $Package "$($Entry.architecture)\RimesRegistrar.exe") $Operation --dll $Entry.dll
    if($LASTEXITCODE){throw "Legacy registration $Operation failed"}
}
function Get-BrokerAutostart {
    $key=[Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Software\Microsoft\Windows\CurrentVersion\Run')
    try { if($key){return $key.GetValue('RimesBroker',$null)} } finally {if($key){$key.Dispose()}}
}
function Restore-BrokerAutostart($Value) {
    $key=[Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Software\Microsoft\Windows\CurrentVersion\Run')
    try {if($null -eq $Value){$key.DeleteValue('RimesBroker',$false)}else{$key.SetValue('RimesBroker',[string]$Value)}} finally {$key.Dispose()}
}
function Get-InstalledAppKey {return 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\RIMES'}
function Read-InstalledAppRegistration {
    $base=[Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine,[Microsoft.Win32.RegistryView]::Registry64)
    $key=$null
    try {
        $key=$base.OpenSubKey((Get-InstalledAppKey))
        if(-not $key){return $null}
        $values=@{}
        foreach($name in $key.GetValueNames()){$values[$name]=[pscustomobject]@{value=$key.GetValue($name,$null,[Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames);kind=$key.GetValueKind($name)}}
        return $values
    } finally {if($key){$key.Dispose()};$base.Dispose()}
}
function Restore-InstalledAppRegistration($Values) {
    $base=[Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine,[Microsoft.Win32.RegistryView]::Registry64)
    $key=$null
    try {
        $path=Get-InstalledAppKey
        $base.DeleteSubKeyTree($path,$false)
        if($null -ne $Values){
            $key=$base.CreateSubKey($path)
            foreach($name in $Values.Keys){$key.SetValue($name,$Values[$name].value,$Values[$name].kind)}
        }
    } finally {if($key){$key.Dispose()};$base.Dispose()}
}
function Get-UninstallCommand([string]$InstallRoot,[string]$LauncherDirectory) {
    $powershell=Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    return '"'+$powershell+'" -NoProfile -STA -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+(Join-Path $LauncherDirectory 'Uninstall-App.ps1')+'" -InstallRoot "'+$InstallRoot+'"'
}
function Write-InstalledAppRegistration([string]$InstallRoot,[string]$Directory,$Manifest,[string]$LauncherDirectory=$Directory) {
    Assert-OwnedVersion $InstallRoot $LauncherDirectory | Out-Null
    if(-not (Test-Path -LiteralPath (Join-Path $LauncherDirectory 'Uninstall-App.ps1') -PathType Leaf)){throw 'The managed uninstall launcher is missing'}
    $base=[Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine,[Microsoft.Win32.RegistryView]::Registry64)
    $key=$null
    try {
        $key=$base.CreateSubKey((Get-InstalledAppKey))
        $key.SetValue('DisplayName','RIMES')
        $key.SetValue('DisplayVersion',[string]$Manifest.version)
        $key.SetValue('Publisher','Scholay')
        $key.SetValue('InstallLocation',$Directory)
        $key.SetValue('DisplayIcon',(Join-Path $Directory 'x64\RimesBroker.exe')+',0')
        $key.SetValue('UninstallString',(Get-UninstallCommand $InstallRoot $LauncherDirectory))
        $key.SetValue('RIMESInstallRoot',$InstallRoot)
        $key.SetValue('RIMESUninstallDirectory',$LauncherDirectory)
        $key.SetValue('RIMESUserSid',[Security.Principal.WindowsIdentity]::GetCurrent().User.Value)
        $key.SetValue('URLInfoAbout','https://github.com/scholay/rimes')
        $key.SetValue('InstallDate',(Get-Date -Format 'yyyyMMdd'))
        $key.SetValue('NoModify',1,[Microsoft.Win32.RegistryValueKind]::DWord)
        $key.SetValue('NoRepair',1,[Microsoft.Win32.RegistryValueKind]::DWord)
        $bytes=(Get-ChildItem -LiteralPath $Directory -Recurse -File | Measure-Object -Property Length -Sum).Sum
        $key.SetValue('EstimatedSize',[int][Math]::Min([int]::MaxValue,[Math]::Ceiling($bytes/1024)),[Microsoft.Win32.RegistryValueKind]::DWord)
    } finally {if($key){$key.Dispose()};$base.Dispose()}
    Assert-InstalledAppRegistration $InstallRoot $Directory $Manifest
}
function Assert-InstalledAppRegistration([string]$InstallRoot,[string]$Directory,$Manifest) {
    $values=Read-InstalledAppRegistration
    if($null -eq $values){throw 'RIMES is missing from Windows Installed Apps'}
    foreach($name in @('DisplayName','DisplayVersion','InstallLocation','RIMESInstallRoot','RIMESUninstallDirectory','UninstallString')){if(-not $values.ContainsKey($name)){throw "Installed Apps entry is incomplete: $name"}}
    if($values.DisplayName.value -ne 'RIMES' -or $values.DisplayVersion.value -ne $Manifest.version -or $values.InstallLocation.value -ne $Directory -or $values.RIMESInstallRoot.value -ne $InstallRoot){throw 'Installed Apps entry does not match the active version'}
    $launcher=[string]$values.RIMESUninstallDirectory.value
    Assert-OwnedVersion $InstallRoot $launcher | Out-Null
    if(-not (Test-Path -LiteralPath (Join-Path $launcher 'Uninstall-App.ps1') -PathType Leaf) -or $values.UninstallString.value -ne (Get-UninstallCommand $InstallRoot $launcher)){throw 'Installed Apps uninstall command is invalid'}
}
function Get-SettingsShortcutPath {return Join-Path ([Environment]::GetFolderPath('Programs')) 'RIMES\RIMES Settings.lnk'}
function Read-SettingsShortcut {
    $path=Get-SettingsShortcutPath
    if(Test-Path -LiteralPath $path -PathType Leaf){return [pscustomobject]@{path=$path;bytes=[IO.File]::ReadAllBytes($path)}}
    return $null
}
function Restore-SettingsShortcut($State) {
    $path=Get-SettingsShortcutPath
    if($null -eq $State){if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path -Force};return}
    if($State.path -ne $path){throw 'Shortcut recovery path mismatch'}
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllBytes($path,[byte[]]$State.bytes)
}
function Test-OwnedSettingsShortcut([string]$InstallRoot,$Shortcut) {
    $versions=[IO.Path]::GetFullPath((Join-Path $InstallRoot 'versions')).TrimEnd('\')+'\'
    return $Shortcut.Arguments -eq '--settings' -and [IO.Path]::GetFileName($Shortcut.TargetPath) -eq 'RimesBroker.exe' -and $Shortcut.TargetPath.StartsWith($versions,[StringComparison]::OrdinalIgnoreCase)
}
function Test-SettingsCommandSupported([string]$Directory) {
    try {
        $help=& (Join-Path $Directory 'x64\RimesBroker.exe') --help 2>&1 | Out-String
        return $LASTEXITCODE -eq 0 -and $help -match '--settings\b'
    } catch {return $false}
}
function Write-SettingsShortcut([string]$InstallRoot,[string]$Directory) {
    if(-not (Test-SettingsCommandSupported $Directory)){
        Remove-OwnedSettingsShortcut $InstallRoot
        Write-Host 'This version does not support the settings launch command. Open settings from the RIMES tray menu; its managed Start Menu entry was removed.'
        return
    }
    $path=Get-SettingsShortcutPath
    $shell=New-Object -ComObject WScript.Shell
    $shortcut=$null
    try {
        $shortcut=$shell.CreateShortcut($path)
        if((Test-Path -LiteralPath $path) -and -not (Test-OwnedSettingsShortcut $InstallRoot $shortcut)){return}
        New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
        $shortcut.TargetPath=Join-Path $Directory 'x64\RimesBroker.exe'
        $shortcut.Arguments='--settings'
        $shortcut.WorkingDirectory=Join-Path $Directory 'x64'
        $shortcut.IconLocation=$shortcut.TargetPath+',0'
        $shortcut.Description='Open RIMES settings'
        $shortcut.Save()
    } finally {if($shortcut){[Runtime.InteropServices.Marshal]::FinalReleaseComObject($shortcut) | Out-Null};[Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell) | Out-Null}
    Assert-SettingsShortcut $InstallRoot $Directory
}
function Assert-SettingsShortcut([string]$InstallRoot,[string]$Directory) {
    $path=Get-SettingsShortcutPath
    $supported=Test-SettingsCommandSupported $Directory
    if(-not $supported -and -not (Test-Path -LiteralPath $path -PathType Leaf)){return}
    if(-not (Test-Path -LiteralPath $path -PathType Leaf)){throw 'The RIMES Start Menu settings shortcut is missing'}
    $shell=New-Object -ComObject WScript.Shell
    $shortcut=$null
    try {
        $shortcut=$shell.CreateShortcut($path)
        if(Test-OwnedSettingsShortcut $InstallRoot $shortcut){
            if(-not $supported){throw 'The RIMES settings shortcut targets a version that lacks the settings command'}
            if($shortcut.TargetPath -ne (Join-Path $Directory 'x64\RimesBroker.exe')){throw 'The RIMES Start Menu shortcut points to a different version'}
        }
        # User-edited shortcuts retain their target and arguments.
    } finally {if($shortcut){[Runtime.InteropServices.Marshal]::FinalReleaseComObject($shortcut) | Out-Null};[Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell) | Out-Null}
}
function Remove-OwnedSettingsShortcut([string]$InstallRoot) {
    $path=Get-SettingsShortcutPath
    if(-not (Test-Path -LiteralPath $path -PathType Leaf)){return}
    $shell=New-Object -ComObject WScript.Shell
    $shortcut=$null
    try {$shortcut=$shell.CreateShortcut($path);if(Test-OwnedSettingsShortcut $InstallRoot $shortcut){Remove-Item -LiteralPath $path -Force}}
    finally {if($shortcut){[Runtime.InteropServices.Marshal]::FinalReleaseComObject($shortcut) | Out-Null};[Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell) | Out-Null}
}
function Get-RimesActiveVersion([string]$Root) {
    # Recovery path for a lost state.json: return the newest version directory that
    # still verifies against PACKAGE.json, so ownership checks are not relaxed.
    $versions=[IO.Path]::GetFullPath((Join-Path $Root 'versions')).TrimEnd('\')
    if(-not (Test-Path -LiteralPath $versions)){return $null}
    foreach($directory in @(Get-ChildItem -LiteralPath $versions -Directory | Sort-Object Name -Descending)){
        if(-not (Test-Path -LiteralPath (Join-Path $directory.FullName 'PACKAGE.json'))){continue}
        try{Read-VerifiedPackage $directory.FullName | Out-Null}catch{continue}
        return $directory.FullName
    }
    return $null
}
