# BUILD_FULL.ps1
# Run from PowerShell in the repo root
# Example: .\BUILD_FULL.ps1

$ErrorActionPreference = "Stop"

$RepoRoot = $PSScriptRoot
$FlutterDir = Join-Path $RepoRoot "flutter"
$ts = Get-Date -Format "ddMMyyyy_HHmmss"

if (-not (Test-Path $FlutterDir)) {
    throw "Flutter folder not found: $FlutterDir"
}

$logDir = Join-Path $RepoRoot "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$buildLog = Join-Path $logDir "jiva_flutter_build_$ts.log"
$failLog = Join-Path $logDir "jiva_flutter_failed_$ts.log"

if (-not (Get-Command flutter -ErrorAction SilentlyContinue)) {
    throw "Flutter not found in PATH. Install Flutter 3.44+ and reopen PowerShell."
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb not found in PATH. Install Android SDK Platform Tools."
}

try {
    Set-Location $FlutterDir

    Write-Host "==> Step 1: flutter create" -ForegroundColor Cyan
    & flutter create . --platforms android,ios --overwrite --verbose 2>&1 | Tee-Object -FilePath $buildLog
    if ($LASTEXITCODE -ne 0) { throw "flutter create failed" }

    Write-Host "==> Step 2: flutter pub get" -ForegroundColor Cyan
    & flutter pub get --verbose 2>&1 | Tee-Object -FilePath $buildLog -Append
    if ($LASTEXITCODE -ne 0) { throw "flutter pub get failed" }

    Write-Host "==> Step 3: flutter test" -ForegroundColor Cyan
    & flutter test --verbose 2>&1 | Tee-Object -FilePath $buildLog -Append
    if ($LASTEXITCODE -ne 0) { throw "flutter test failed" }

    Write-Host "==> Step 4: flutter build apk" -ForegroundColor Cyan
    & flutter build apk --debug --verbose 2>&1 | Tee-Object -FilePath $buildLog -Append
    if ($LASTEXITCODE -ne 0) { throw "flutter build apk failed" }

    $apkPath = Join-Path $FlutterDir "build\app\outputs\flutter-apk\app-debug.apk"

    if (-not (Test-Path $apkPath)) {
        throw "APK not found: $apkPath"
    }

    Write-Host "==> Step 5: adb install" -ForegroundColor Cyan
    & adb install -r $apkPath 2>&1 | Tee-Object -FilePath $buildLog -Append
    if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

    Write-Host ""
    Write-Host "==> SUCCESS: Jiva Flutter app installed on device." -ForegroundColor Green
    Write-Host "==> Build log: $buildLog" -ForegroundColor Green
}
catch {
    $err = $_.Exception.Message
    Write-Host ""
    Write-Host "==> FAILED: $err" -ForegroundColor Red
    $err | Out-File $failLog -Encoding utf8
    Write-Host "==> Fail log: $failLog" -ForegroundColor Red
    throw
}
