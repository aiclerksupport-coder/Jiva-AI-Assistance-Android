# BUILD_FULL.ps1
# First-time friendly Flutter build/install helper for Jiva
# Features:
# - preflight checks for Flutter, Android SDK, Java, adb
# - device detection and setup hints
# - safe generate flow (won't overwrite existing host unless forced)
# - verbose logs with ddMMyyyy_HHmmss naming
# - failure log with concise message
# - optional CheckOnly, SkipTest, NoInstall, ForceCreate, Help modes
#
# Usage:
#   .\BUILD_FULL.ps1
#   .\BUILD_FULL.ps1 -CheckOnly
#   .\BUILD_FULL.ps1 -SkipTest
#   .\BUILD_FULL.ps1 -NoInstall
#   .\BUILD_FULL.ps1 -ForceCreate
#   .\BUILD_FULL.ps1 -Help

param(
    [switch]$CheckOnly,
    [switch]$SkipTest,
    [switch]$NoInstall,
    [switch]$ForceCreate,
    [switch]$Help
)

$ErrorActionPreference = "Stop"

function Write-Status {
    param([string]$Message, [string]$Color = "Cyan")
    Write-Host "==> $Message" -ForegroundColor $Color
}

function Invoke-CheckedCommand {
    param(
        [string]$File,
        [string[]]$Arguments,
        [string]$StepName,
        [string]$LogPath
    )

    Write-Status $StepName
    & $File @Arguments 2>&1 | Tee-Object -FilePath $LogPath -Append
    if ($LASTEXITCODE -ne 0) {
        throw "$StepName failed. See log: $LogPath"
    }
}

if ($Help) {
    Write-Host "Jiva Flutter setup/build helper" -ForegroundColor Green
    Write-Host "Usage:" -ForegroundColor Yellow
    Write-Host "  .\BUILD_FULL.ps1" -ForegroundColor White
    Write-Host "  .\BUILD_FULL.ps1 -CheckOnly" -ForegroundColor White
    Write-Host "  .\BUILD_FULL.ps1 -SkipTest" -ForegroundColor White
    Write-Host "  .\BUILD_FULL.ps1 -NoInstall" -ForegroundColor White
    Write-Host "  .\BUILD_FULL.ps1 -ForceCreate" -ForegroundColor White
    Write-Host "" -ForegroundColor White
    Write-Host "What it does:" -ForegroundColor Yellow
    Write-Host "  - checks Flutter/Android SDK/Java/adb" -ForegroundColor White
    Write-Host "  - creates Flutter Android/iOS host if missing" -ForegroundColor White
    Write-Host "  - runs pub get and tests" -ForegroundColor White
    Write-Host "  - builds a debug APK" -ForegroundColor White
    Write-Host "  - installs to connected device/emulator" -ForegroundColor White
    exit 0
}

$RepoRoot = $PSScriptRoot
$FlutterDir = Join-Path $RepoRoot "flutter"
$ts = Get-Date -Format "ddMMyyyy_HHmmss"
$logDir = Join-Path $RepoRoot "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$buildLog = Join-Path $logDir "jiva_flutter_build_$ts.log"
$failLog = Join-Path $logDir "jiva_flutter_failed_$ts.log"

function Assert-Tool {
    param([string]$Name, [string]$CommandName)
    if (-not (Get-Command $CommandName -ErrorAction SilentlyContinue)) {
        throw "$Name not found in PATH. Install it and reopen PowerShell."
    }
}

try {
    Write-Status "Starting Jiva Flutter setup / build checks" "Green"
    Write-Status "Repo root: $RepoRoot"
    Write-Status "Flutter dir: $FlutterDir"
    Write-Status "Log file: $buildLog"

    # --- Check required tools ---
    Assert-Tool "Flutter" "flutter"
    Assert-Tool "ADB" "adb"

    # Java check (Android toolchain often requires Java)
    $javaCheck = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javaCheck) {
        throw "Java not found in PATH. Install JDK 17 and reopen PowerShell."
    }

    # Android SDK detection (common env vars)
    $androidHome = $env:ANDROID_HOME
    $androidSdkRoot = $env:ANDROID_SDK_ROOT
    if (-not $androidHome -and -not $androidSdkRoot) {
        Write-Host "WARNING: ANDROID_HOME / ANDROID_SDK_ROOT is not set." -ForegroundColor Yellow
        Write-Host "Android SDK may still work if installed in a default location." -ForegroundColor Yellow
    }

    # Verify flutter works
    & flutter --version 2>&1 | Tee-Object -FilePath $buildLog
    if ($LASTEXITCODE -ne 0) { throw "flutter --version failed" }

    # Verify adb sees a connected device or emulator
    & adb devices 2>&1 | Tee-Object -FilePath $buildLog -Append
    if ($LASTEXITCODE -ne 0) { throw "adb devices failed" }

    $deviceList = (& adb devices 2>&1 | Select-String -Pattern "device$|device\s*$|emulator" -SimpleMatch)
    if (-not $deviceList) {
        Write-Host "" -ForegroundColor Yellow
        Write-Host "No Android device/emulator is currently connected." -ForegroundColor Yellow
        Write-Host "Please do one of the following:" -ForegroundColor Yellow
        Write-Host "  1. Connect a real phone with USB debugging enabled" -ForegroundColor White
        Write-Host "  2. Start an Android emulator from Android Studio" -ForegroundColor White
        Write-Host "  3. Run: adb devices" -ForegroundColor White
        Write-Host "Then rerun this script." -ForegroundColor Yellow
        if (-not $CheckOnly) {
            throw "No Android device/emulator detected."
        }
    }

    if ($CheckOnly) {
        Write-Host "" -ForegroundColor Green
        Write-Host "Preflight checks passed. You are ready to build." -ForegroundColor Green
        exit 0
    }

    if (-not (Test-Path $FlutterDir)) {
        throw "Flutter folder not found: $FlutterDir"
    }

    $androidHostExists = Test-Path (Join-Path $FlutterDir "android")
    $iosHostExists = Test-Path (Join-Path $FlutterDir "ios")

    if (-not $androidHostExists -or -not $iosHostExists -or $ForceCreate) {
        Write-Status "Creating Flutter host in $FlutterDir" "Cyan"
        if (-not $androidHostExists -and -not $iosHostExists -and -not $ForceCreate) {
            & flutter create . --platforms android,ios --verbose 2>&1 | Tee-Object -FilePath $buildLog
            if ($LASTEXITCODE -ne 0) { throw "flutter create failed" }
        }
        elseif ($ForceCreate) {
            Write-Host "ForceCreate enabled - regenerating Flutter platform host." -ForegroundColor Yellow
            & flutter create . --platforms android,ios --overwrite --verbose 2>&1 | Tee-Object -FilePath $buildLog
            if ($LASTEXITCODE -ne 0) { throw "flutter create --overwrite failed" }
        }
    }
    else {
        Write-Status "Flutter Android/iOS host already exists. Skipping create step." "Green"
    }

    # --- Android manifest setup warning ---
    $manifestPath = Join-Path $FlutterDir "android\app\src\main\AndroidManifest.xml"
    if (Test-Path $manifestPath) {
        $manifestContent = Get-Content $manifestPath -Raw
        $requiredPermissions = @(
            "android.permission.INTERNET",
            "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE"
        )

        $missing = @()
        foreach ($perm in $requiredPermissions) {
            if ($manifestContent -notmatch [regex]::Escape($perm)) {
                $missing += $perm
            }
        }

        if ($missing.Count -gt 0) {
            Write-Host "" -ForegroundColor Yellow
            Write-Host "Android manifest is missing required permissions:" -ForegroundColor Yellow
            $missing | ForEach-Object { Write-Host "  - $_" -ForegroundColor White }
            Write-Host "Please apply the setup from flutter/README.md before build/install." -ForegroundColor Yellow
            Write-Host "Relevant items: permissions, foreground service, notification receiver setup." -ForegroundColor Yellow
        }
        else {
            Write-Status "Android manifest looks configured for the required Jiva permissions." "Green"
        }
    }
    else {
        Write-Host "Android manifest not found yet. flutter create may still be working or the project was not generated correctly." -ForegroundColor Yellow
    }

    # --- Dependency install ---
    Invoke-CheckedCommand -File "flutter" -Arguments @("pub","get","--verbose") -StepName "Running flutter pub get" -LogPath $buildLog

    # --- Optional tests ---
    if (-not $SkipTest) {
        Invoke-CheckedCommand -File "flutter" -Arguments @("test","--verbose") -StepName "Running flutter test" -LogPath $buildLog
    }
    else {
        Write-Status "Skipping flutter test because -SkipTest was used." "Yellow"
    }

    # --- Build the debug APK ---
    Invoke-CheckedCommand -File "flutter" -Arguments @("build","apk","--debug","--verbose") -StepName "Building debug APK" -LogPath $buildLog

    $apkPath = Join-Path $FlutterDir "build\app\outputs\flutter-apk\app-debug.apk"
    if (-not (Test-Path $apkPath)) {
        throw "APK not found after build: $apkPath"
    }

    # --- Install if allowed ---
    if (-not $NoInstall) {
        Write-Status "Installing APK to connected Android device/emulator" "Cyan"
        & adb install -r $apkPath 2>&1 | Tee-Object -FilePath $buildLog -Append
        if ($LASTEXITCODE -ne 0) {
            throw "adb install failed. See log: $buildLog"
        }
        Write-Status "APK installed successfully." "Green"
    }
    else {
        Write-Status "Installation was skipped because -NoInstall was used." "Yellow"
    }

    Write-Host "" -ForegroundColor Green
    Write-Host "==> BUILD SUMMARY" -ForegroundColor Green
    Write-Host "    Flutter project: $FlutterDir" -ForegroundColor White
    Write-Host "    APK path: $apkPath" -ForegroundColor White
    Write-Host "    Build log: $buildLog" -ForegroundColor White
    if (-not $NoInstall) {
        Write-Host "    Device install: completed" -ForegroundColor White
    }
    else {
        Write-Host "    Device install: skipped" -ForegroundColor White
    }

    exit 0
}
catch {
    $err = $_.Exception.Message
    Write-Host "" -ForegroundColor Red
    Write-Host "==> FAILED: $err" -ForegroundColor Red
    $err | Out-File $failLog -Encoding utf8
    Write-Host "==> Fail log: $failLog" -ForegroundColor Red
    Write-Host "" -ForegroundColor Yellow
    Write-Host "Troubleshooting tips:" -ForegroundColor Yellow
    Write-Host "  - Make sure Flutter and Android SDK are installed" -ForegroundColor White
    Write-Host "  - Start an Android emulator or enable USB debugging on your phone" -ForegroundColor White
    Write-Host "  - Verify Android manifest setup in flutter/README.md" -ForegroundColor White
    Write-Host "  - Re-run with -CheckOnly to verify environment without building" -ForegroundColor White
    exit 1
}
