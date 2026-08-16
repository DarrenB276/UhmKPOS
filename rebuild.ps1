# Rebuilds both APKs. Run this after replacing app/google-services.json with your real
# Firebase config, or after changing anything in the app.
#
#   cd <project-folder>; .\rebuild.ps1
#
# Add -Install to push the test build straight onto a connected phone or emulator.

param(
    [switch]$Install,
    [switch]$ReleaseOnly
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:USERPROFILE\AppData\Local\Android\Sdk"

if (-not (Test-Path $env:JAVA_HOME)) {
    Write-Host "Could not find the JDK bundled with Android Studio at:" -ForegroundColor Red
    Write-Host "  $env:JAVA_HOME" -ForegroundColor Red
    Write-Host "Install Android Studio, or edit JAVA_HOME at the top of this script." -ForegroundColor Red
    exit 1
}

# Warn early rather than letting the build fail with a cryptic plugin error.
$gs = Join-Path $root "app\google-services.json"
if (Test-Path $gs) {
    $json = Get-Content $gs -Raw
    if ($json -match "uhmk-pos-placeholder") {
        Write-Host ""
        Write-Host "  Heads up: google-services.json is still the placeholder." -ForegroundColor Yellow
        Write-Host "  The app will build and run, but stays offline-only." -ForegroundColor Yellow
        Write-Host "  See FIREBASE_SETUP.md to connect it." -ForegroundColor Yellow
        Write-Host ""
    } elseif ($json -notmatch "com\.uhmk\.pos\.debug") {
        Write-Host ""
        Write-Host "  Your google-services.json has no entry for com.uhmk.pos.debug." -ForegroundColor Yellow
        Write-Host "  The test build will fail. Add the second Android app in the Firebase" -ForegroundColor Yellow
        Write-Host "  console and re-download the file (step 2 of FIREBASE_SETUP.md)." -ForegroundColor Yellow
        Write-Host ""
    }
}

$targets = if ($ReleaseOnly) { @("assembleRelease") } else { @("assembleDebug", "assembleRelease") }

Write-Host "Building $($targets -join ' and ')..." -ForegroundColor Cyan
# Build one variant at a time. Room writes both variants to the same schema directory and running
# their KSP tasks concurrently can leave a temporarily empty JSON schema on Windows.
foreach ($target in $targets) {
    & .\gradlew.bat $target --console=plain
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Build failed. Scroll up for the first line starting with 'e:'." -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

Write-Host ""
Write-Host "Done." -ForegroundColor Green
foreach ($pair in @(@("debug", "app-debug.apk"), @("release", "app-release.apk"))) {
    $apk = Join-Path $root "app\build\outputs\apk\$($pair[0])\$($pair[1])"
    if (Test-Path $apk) {
        $mb = "{0:N1}" -f ((Get-Item $apk).Length / 1MB)
        Write-Host ("  {0,-8} {1}  ({2} MB)" -f $pair[0], $apk, $mb)
    }
}

if ($Install) {
    $adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
    $apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
    Write-Host ""
    Write-Host "Installing the test build..." -ForegroundColor Cyan
    & $adb install -r $apk
}
