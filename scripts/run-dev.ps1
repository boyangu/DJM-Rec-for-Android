# Build the debug app, start the emulator if needed, install and launch.
#
#   .\scripts\run-dev.ps1              build, install, launch
#   .\scripts\run-dev.ps1 -NoBuild     reinstall the last build and launch
#   .\scripts\run-dev.ps1 -Logs        also stream the app's log afterwards (Ctrl+C to stop)
#
# Debug builds on an emulator publish a demo mixer (usb/DemoMixer.kt) in place of USB hardware,
# playing a synthetic 124 BPM loop through the real audio path, so every screen -- meters,
# waveform, recording, the battery saver screen -- works without a mixer.
#
# One-time setup (all under %LOCALAPPDATA%\Android, no admin rights): JDK 17 in jdk-17, the SDK in
# Sdk with platform-tools, emulator, platforms/android-35, build-tools/35.0.0,
# ndk/26.1.10909125, cmake/3.22.1 and system-images/android-35/google_apis/x86_64, and an AVD
# named SetRecorder. local.properties points Gradle at the SDK.
param(
    [switch]$NoBuild,
    [switch]$Logs,
    [string]$Avd = "SetRecorder"
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$env:JAVA_HOME = Join-Path $env:LOCALAPPDATA "Android\jdk-17"
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$emulator = Join-Path $env:ANDROID_HOME "emulator\emulator.exe"
$package = "com.audiopro.djmrec.debug"

foreach ($path in $env:JAVA_HOME, $adb, $emulator) {
    if (-not (Test-Path $path)) { throw "Missing $path -- see the setup notes at the top of this script." }
}

# Start the emulator unless one is already attached.
$running = & $adb devices | Select-String "^emulator-\d+\s+device"
if (-not $running) {
    Write-Host "Starting emulator $Avd..."
    Start-Process -FilePath $emulator -ArgumentList "-avd", $Avd, "-gpu", "host", "-no-snapshot-save"
}

if (-not $NoBuild) {
    Write-Host "Building debug APK..."
    Push-Location $root
    try {
        & .\gradlew.bat assembleDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Build failed" }
    } finally { Pop-Location }
}

Write-Host "Waiting for the emulator to finish booting..."
& $adb wait-for-device
while ((& $adb shell getprop sys.boot_completed 2>$null) -notmatch "1") { Start-Sleep -Seconds 2 }

$apk = Get-ChildItem (Join-Path $root "app\build\outputs\apk\debug") -Filter *.apk | Select-Object -First 1
if (-not $apk) { throw "No debug APK found; run without -NoBuild." }
Write-Host "Installing $($apk.Name)..."
& $adb install -r -g $apk.FullName
if ($LASTEXITCODE -ne 0) { throw "Install failed" }

# -g above grants runtime permissions (microphone, notifications) so no dialogs interrupt.
& $adb shell am start -n "$package/com.audiopro.djmrec.MainActivity" | Out-Null
Write-Host "Launched. The demo mixer connects on its own; press Record to try a set."

if ($Logs) {
    $appPid = (& $adb shell pidof $package).Trim()
    & $adb logcat --pid=$appPid
}
