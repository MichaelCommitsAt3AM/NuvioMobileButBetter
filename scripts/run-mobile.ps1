<#
.SYNOPSIS
Builds, installs and launches the Nuvio debug app on Android from Windows.

.DESCRIPTION
Windows counterpart to scripts/run-mobile.sh, which only runs on bash hosts.
This script drives the Android SDK tools (adb.exe / emulator.exe) and
gradlew.bat directly, so it works from a normal PowerShell prompt.

.PARAMETER Platform
Only "android" is supported on Windows; iOS is built on macOS.

.PARAMETER TargetOrFlavor
"e" for an emulator, "p" for a connected physical device, or a distribution
("full" / "playstore"). Defaults to an emulator with the "full" flavor.

.PARAMETER Distribution
Distribution flavor: "full" (default) or "playstore".

.EXAMPLE
./scripts/run-mobile.ps1 android p full

.EXAMPLE
./scripts/run-mobile.ps1 android e playstore
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('android')]
    [string]$Platform = 'android',

    [Parameter(Position = 1)]
    [string]$TargetOrFlavor,

    [Parameter(Position = 2)]
    [ValidateSet('full', 'playstore')]
    [string]$Distribution
)

$ErrorActionPreference = 'Stop'

$RootDir = Split-Path -Parent $PSScriptRoot
$GradlewBat = Join-Path $RootDir 'gradlew.bat'
$AndroidAppId = 'com.nuviodebug.com'
$AndroidActivity = 'com.nuvio.app.MainActivity'

function Write-Usage {
    @'
Usage:
  ./scripts/run-mobile.ps1 android [e|p] [full|playstore]
  ./scripts/run-mobile.ps1 android [full|playstore]

Builds the debug app for the selected distribution, installs it on the running
Android emulators or connected physical devices, and launches it.
'@ | Write-Host
}

function Stop-WithError([string]$Message) {
    [Console]::Error.WriteLine($Message)
    exit 1
}

function Resolve-AndroidTool([string]$Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $sdkRoots = @()
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if (-not [string]::IsNullOrWhiteSpace($sdk)) {
            $sdkRoots += $sdk
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $sdkRoots += (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    }

    $candidates = @()
    foreach ($sdk in $sdkRoots) {
        $candidates += (Join-Path $sdk "platform-tools\$Name.exe")
        $candidates += (Join-Path $sdk "emulator\$Name.exe")
    }
    $candidates += "C:\platform-tools\$Name.exe"

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }
    return $null
}

function Get-AndroidDevices {
    param([string]$Adb)

    $lines = & $Adb devices 2>$null
    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if ($trimmed -match '^(\S+)\s+device$') {
            $Matches[1]
        }
    }
}

function Get-BootedEmulatorSerials([string]$Adb) {
    Get-AndroidDevices -Adb $Adb | Where-Object { $_ -like 'emulator-*' }
}

function Get-PhysicalDeviceSerials([string]$Adb) {
    Get-AndroidDevices -Adb $Adb | Where-Object { $_ -notlike 'emulator-*' }
}

function Build-AndroidApk([string]$Flavor) {
    $flavorPart = switch ($Flavor) {
        'full' { 'Full' }
        'playstore' { 'Playstore' }
    }
    $apkPath = Join-Path $RootDir "androidApp\build\outputs\apk\$Flavor\debug\androidApp-$Flavor-debug.apk"

    Write-Host "Building Android $Flavor debug APK..."
    & $GradlewBat ":androidApp:assemble${flavorPart}Debug" | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Stop-WithError "Gradle build failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $apkPath)) {
        Stop-WithError "Expected APK not found at: $apkPath"
    }
    return $apkPath
}

function Install-AndLaunch([string]$Adb, [string]$DeviceLabel, [string]$ApkPath, [string[]]$Serials) {
    foreach ($serial in $Serials) {
        Write-Host "Installing on $DeviceLabel $serial..."
        & $Adb -s $serial install -r $ApkPath
        if ($LASTEXITCODE -ne 0) {
            Stop-WithError "adb install failed for $serial (exit code $LASTEXITCODE)."
        }

        Write-Host "Launching app on $serial..."
        & $Adb -s $serial shell am start -n "$AndroidAppId/$AndroidActivity"
        if ($LASTEXITCODE -ne 0) {
            Stop-WithError "Could not launch the app on $serial (exit code $LASTEXITCODE)."
        }
    }
}

function Wait-ForEmulatorBoot([string]$Adb, [string[]]$Serials) {
    foreach ($serial in $Serials) {
        Write-Host "Waiting for $serial to finish booting..."
        & $Adb -s $serial wait-for-device | Out-Null
        for ($attempt = 0; $attempt -lt 300; $attempt++) {
            $state = (& $Adb -s $serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
            if ($state -eq '1') {
                break
            }
            Start-Sleep -Seconds 1
        }
    }
}

function Invoke-AndroidEmulator([string]$Adb, [string]$Emulator, [string]$Flavor) {
    $serials = @(Get-BootedEmulatorSerials -Adb $Adb)

    if ($serials.Count -gt 0) {
        Write-Host "Using running Android emulators: $($serials -join ', ')"
    } else {
        $avds = @(& $Emulator -list-avds | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        if ($avds.Count -eq 0) {
            Stop-WithError "No Android emulators available. Create an AVD first, then rerun: ./scripts/run-mobile.ps1 android e"
        }

        foreach ($avd in $avds) {
            Write-Host "Opening Android emulator $avd..."
            Start-Process -FilePath $Emulator -ArgumentList "@$avd" | Out-Null
        }

        Write-Host "Waiting for Android emulators to boot..."
        $deadline = (Get-Date).AddMinutes(10)
        while ($serials.Count -lt $avds.Count -and (Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 2
            $serials = @(Get-BootedEmulatorSerials -Adb $Adb)
        }
        if ($serials.Count -lt $avds.Count) {
            Stop-WithError "Timed out waiting for the Android emulators to boot."
        }
    }

    Wait-ForEmulatorBoot -Adb $Adb -Serials $serials

    $apkPath = Build-AndroidApk -Flavor $Flavor
    Install-AndLaunch -Adb $Adb -DeviceLabel 'emulator' -ApkPath $apkPath -Serials $serials
}

function Invoke-AndroidPhysical([string]$Adb, [string]$Flavor) {
    $serials = @(Get-PhysicalDeviceSerials -Adb $Adb)
    if ($serials.Count -eq 0) {
        Stop-WithError @'
No Android physical devices connected.
Enable USB debugging (or wireless debugging), unlock the device, then rerun:
  ./scripts/run-mobile.ps1 android p
'@
    }

    Write-Host "Using connected Android physical devices: $($serials -join ', ')"

    $apkPath = Build-AndroidApk -Flavor $Flavor
    Install-AndLaunch -Adb $Adb -DeviceLabel 'physical device' -ApkPath $apkPath -Serials $serials
}

# Argument handling mirrors scripts/run-mobile.sh: the second argument may be a
# target (e/p) or a distribution, and the third is always the distribution.
$target = 'e'
$flavor = 'full'
if (-not [string]::IsNullOrWhiteSpace($TargetOrFlavor)) {
    switch ($TargetOrFlavor) {
        'e' { $target = 'e' }
        'p' { $target = 'p' }
        'full' { $flavor = 'full' }
        'playstore' { $flavor = 'playstore' }
        default {
            [Console]::Error.WriteLine("Unknown Android target or flavor: $TargetOrFlavor")
            Write-Usage
            exit 1
        }
    }
}
if (-not [string]::IsNullOrWhiteSpace($Distribution)) {
    $flavor = $Distribution
}

$adb = Resolve-AndroidTool 'adb'
if (-not $adb) {
    Stop-WithError "Missing required command: adb (install Android platform-tools or set ANDROID_HOME)."
}

switch ($target) {
    'e' {
        $emulator = Resolve-AndroidTool 'emulator'
        if (-not $emulator) {
            Stop-WithError "Missing required command: emulator (install the Android emulator or set ANDROID_HOME)."
        }
        Invoke-AndroidEmulator -Adb $adb -Emulator $emulator -Flavor $flavor
    }
    'p' {
        Invoke-AndroidPhysical -Adb $adb -Flavor $flavor
    }
}
