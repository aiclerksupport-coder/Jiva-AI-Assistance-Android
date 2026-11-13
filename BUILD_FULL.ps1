# BUILD_FULL.ps1
# First-time friendly Flutter build/install helper for Jiva.
# Safe-recovery policy: this script may clean generated build caches and refresh
# packages, but it NEVER edits Dart/Kotlin source, app logic, or the manifest.
# Test failures are reported for a human to fix; they are not auto-rewritten.
#
# Usage:
#   .\BUILD_FULL.ps1
#   .\BUILD_FULL.ps1 -CheckOnly
#   .\BUILD_FULL.ps1 -SkipTest
#   .\BUILD_FULL.ps1 -NoInstall
#   .\BUILD_FULL.ps1 -ForceCreate
#   .\BUILD_FULL.ps1 -RepairSafe
#   .\BUILD_FULL.ps1 -Help

param(
    [switch]$CheckOnly,
    [switch]$SkipTest,
    [switch]$NoInstall,
    [switch]$ForceCreate,
    [switch]$RepairSafe,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'

function Write-Status {
    param([string]$Message, [string]$Color = 'Cyan')
    Write-Host "==> $Message" -ForegroundColor $Color
}

function Assert-Tool {
    param([string]$Name, [string]$CommandName)
    if (-not (Get-Command $CommandName -ErrorAction SilentlyContinue)) {
        throw "$Name was not found in PATH. Install it, reopen PowerShell, and try again."
    }
}

function Invoke-LoggedCommand {
    param(
        [string]$File,
        [string[]]$Arguments,
        [string]$StepName,
        [string]$LogPath
    )

    $script:FailedStep = $StepName
    Write-Status $StepName
    $output = @(& $File @Arguments 2>&1 | Tee-Object -FilePath $LogPath -Append)
    $exitCode = $LASTEXITCODE
    $script:LastCommandOutput = @($output | ForEach-Object { $_.ToString() })
    if ($exitCode -ne 0) {
        throw "$StepName failed with exit code $exitCode."
    }
}

function Get-Diagnosis {
    param([string]$StepName, [string[]]$OutputLines)

    $text = $OutputLines -join "`n"
    $hints = [System.Collections.Generic.List[string]]::new()

    if ($text -match '(?i)version solving failed|could not resolve|pub get failed|dependency') {
        $hints.Add('Dependency resolution failed. Check internet access and package constraints, then review flutter/pubspec.yaml and the full log.')
        $hints.Add('Safe next step: run flutter pub get again after network access is restored. This script will not change dependency versions automatically.')
    }
    if ($text -match '(?i)no connected devices|no devices found|device.*offline|unauthorized') {
        $hints.Add('Device issue: unlock the phone, enable Developer options > USB debugging, accept the RSA prompt, or start an emulator; then check adb devices.')
    }
    if ($text -match '(?i)licenses.*not accepted|sdkmanager|Android SDK') {
        $hints.Add('Android SDK issue: open Android Studio > SDK Manager, install the required SDK/platform tools, and accept Android SDK licenses with flutter doctor --android-licenses.')
    }
    if ($text -match '(?i)unsupported class file|requires Java|invalid source release|JAVA_HOME|jvm target') {
        $hints.Add('Java/Gradle mismatch: use the JDK version required by the Flutter/Android Gradle toolchain (Android Studio bundled JDK is usually a good choice), then check flutter doctor -v.')
    }
    if ($text -match '(?i)manifest merger failed|AndroidManifest.xml') {
        $hints.Add('Android manifest/Gradle setup issue: compare flutter/android/app/src/main/AndroidManifest.xml with the Android setup section in flutter/README.md. The script does not rewrite it automatically.')
    }
    if ($text -match '(?i)BUILD FAILED|gradle task.*failed|execution failed for task') {
        $hints.Add('Gradle build issue: inspect the FIRST specific Gradle error above the final BUILD FAILED line in the full log. After reviewing it, you can choose the safe cache-clean/rebuild option.')
    }
    if ($text -match '(?i)Expected:|Actual:|Which:|Test failed|Some tests failed|Exception|Error:') {
        $hints.Add('A test or code-level error may need a source fix. The script will not modify Dart/Kotlin logic automatically; use the reported test/file/line and review the failing expectation.')
    }
    if ($hints.Count -eq 0) {
        $hints.Add("Review the output around the failed step '$StepName' in the full build log. No known safe automatic fix was identified.")
        $hints.Add('Run flutter doctor -v for environment diagnostics. The script will not make speculative source-code changes.')
    }

    return $hints
}

function Write-FailureReport {
    param([string]$StepName, [string]$ErrorMessage, [string]$OutputLog, [string]$FailureLog)

    $lines = @()
    if (Test-Path $OutputLog) {
        $lines = @(Get-Content $OutputLog -Tail 160 -ErrorAction SilentlyContinue)
    }
    $hints = @(Get-Diagnosis -StepName $StepName -OutputLines $lines)
    $report = @(
        "Jiva Flutter build failure",
        "Date: $(Get-Date -Format 'ddMMyyyy HH:mm:ss')",
        "Failed step: $StepName",
        "Error: $ErrorMessage",
        "Full log: $OutputLog",
        '',
        'Recommendations:'
    ) + @($hints | ForEach-Object { "- $_" }) + @('', 'Recent output:') + $lines

    $report | Out-File -FilePath $FailureLog -Encoding utf8
    Write-Host ''
    Write-Host "==> FAILED at: $StepName" -ForegroundColor Red
    Write-Host "==> $ErrorMessage" -ForegroundColor Red
    Write-Host '==> Recommendations:' -ForegroundColor Yellow
    foreach ($hint in $hints) { Write-Host "  - $hint" -ForegroundColor Yellow }
    Write-Host "==> Full verbose log: $OutputLog" -ForegroundColor Yellow
    Write-Host "==> Failure report: $FailureLog" -ForegroundColor Yellow
}

if ($Help) {
    Write-Host 'Jiva Flutter build helper' -ForegroundColor Green
    Write-Host '  .\BUILD_FULL.ps1                 Build, test, and install to a connected device'
    Write-Host '  .\BUILD_FULL.ps1 -CheckOnly      Check tools and device without building'
    Write-Host '  .\BUILD_FULL.ps1 -SkipTest       Skip Flutter tests'
    Write-Host '  .\BUILD_FULL.ps1 -NoInstall      Build APK but do not install it'
    Write-Host '  .\BUILD_FULL.ps1 -ForceCreate    Regenerate platform host (backup your custom host first)'
    Write-Host '  .\BUILD_FULL.ps1 -RepairSafe     Allow safe clean + dependency refresh before build'
    Write-Host ''
    Write-Host 'Safe recovery only cleans generated build output and fetches declared packages.' -ForegroundColor Yellow
    Write-Host 'It never edits Dart/Kotlin logic or AndroidManifest.xml. Test failures need human review.' -ForegroundColor Yellow
    exit 0
}

$RepoRoot = $PSScriptRoot
$FlutterDir = Join-Path $RepoRoot 'flutter'
$timestamp = Get-Date -Format 'ddMMyyyy_HHmmss'
$logDir = Join-Path $RepoRoot 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$buildLog = Join-Path $logDir "jiva_flutter_build_$timestamp.log"
$failLog = Join-Path $logDir "jiva_flutter_failed_$timestamp.log"
$script:FailedStep = 'Preflight checks'
$script:LastCommandOutput = @()

try {
    if (-not (Test-Path $FlutterDir)) { throw "Flutter folder not found: $FlutterDir" }

    Write-Status 'Checking tools needed for Jiva Flutter' 'Green'
    Write-Status "Repository: $RepoRoot"
    Write-Status "Build log: $buildLog"
    Assert-Tool 'Flutter SDK' 'flutter'
    Assert-Tool 'Android Debug Bridge (adb)' 'adb'
    Assert-Tool 'Java/JDK' 'java'

    Invoke-LoggedCommand -File 'flutter' -Arguments @('--version') -StepName 'Checking Flutter version' -LogPath $buildLog
    Invoke-LoggedCommand -File 'flutter' -Arguments @('doctor', '-v') -StepName 'Checking Flutter/Android toolchain (doctor)' -LogPath $buildLog

    $script:FailedStep = 'Checking Android device'
    Write-Status 'Checking connected phone/emulator'
    $deviceOutput = @(& adb devices 2>&1 | Tee-Object -FilePath $buildLog -Append)
    $adbExit = $LASTEXITCODE
    if ($adbExit -ne 0) { throw 'adb devices failed.' }
    $readyDevices = @($deviceOutput | ForEach-Object { $_.ToString() } | Where-Object { $_ -match '^\S+\s+device\s*$' })
    $badDevices = @($deviceOutput | ForEach-Object { $_.ToString() } | Where-Object { $_ -match '^\S+\s+(unauthorized|offline)\s*$' })

    if ($badDevices.Count -gt 0) {
        Write-Host 'A device is listed but not ready:' -ForegroundColor Yellow
        $badDevices | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
        Write-Host 'Unlock the phone, accept the USB debugging prompt, or reconnect it.' -ForegroundColor Yellow
    }
    if ($readyDevices.Count -eq 0) {
        Write-Host 'No ready Android device/emulator found. Connect a phone with USB debugging enabled or start an emulator.' -ForegroundColor Yellow
        if (-not $CheckOnly -and -not $NoInstall) { throw 'No ready Android device/emulator; cannot install the APK.' }
    }
    elseif ($readyDevices.Count -gt 1) {
        Write-Host 'Multiple devices are connected. Set ANDROID_SERIAL to the desired device ID before running install.' -ForegroundColor Yellow
        $readyDevices | ForEach-Object { Write-Host "  $_" -ForegroundColor White }
        if (-not $CheckOnly -and -not $NoInstall) { throw 'Multiple ready devices detected. Set ANDROID_SERIAL to one device ID and rerun.' }
    }

    if ($CheckOnly) {
        Write-Host ''
        Write-Host 'Preflight finished. Review flutter doctor output above; fix any issues it reports before building.' -ForegroundColor Green
        Write-Host "Full diagnostic log: $buildLog" -ForegroundColor Green
        exit 0
    }

    $androidDir = Join-Path $FlutterDir 'android'
    $iosDir = Join-Path $FlutterDir 'ios'
    if ($ForceCreate) {
        Write-Host 'WARNING: -ForceCreate may change generated platform files. Back up any custom Android/iOS changes first.' -ForegroundColor Yellow
        $answer = Read-Host 'Type FORCE to continue regenerating the Flutter host'
        if ($answer -cne 'FORCE') { throw 'Platform host regeneration cancelled; no files were intentionally overwritten.' }
        Invoke-LoggedCommand -File 'flutter' -Arguments @('create', '.', '--platforms', 'android,ios', '--overwrite', '--verbose') -StepName 'Regenerating Flutter platform host' -LogPath $buildLog
    }
    elseif (-not (Test-Path $androidDir) -or -not (Test-Path $iosDir)) {
        Write-Status 'Generating missing Flutter platform host files (existing host files will not be overwritten)' 'Cyan'
        Invoke-LoggedCommand -File 'flutter' -Arguments @('create', '.', '--platforms', 'android,ios', '--verbose') -StepName 'Creating Flutter Android/iOS host' -LogPath $buildLog
    }
    else {
        Write-Status 'Flutter platform host already exists; keeping existing files' 'Green'
    }

    $manifestPath = Join-Path $FlutterDir 'android\app\src\main\AndroidManifest.xml'
    if (Test-Path $manifestPath) {
        $manifest = Get-Content $manifestPath -Raw
        $requiredManifestItems = @(
            'android.permission.INTERNET',
            'android.permission.RECORD_AUDIO',
            'android.permission.POST_NOTIFICATIONS',
            'android.permission.RECEIVE_BOOT_COMPLETED',
            'android.permission.FOREGROUND_SERVICE',
            'android.permission.FOREGROUND_SERVICE_MICROPHONE',
            'com.pravera.flutter_foreground_task.service.ForegroundService'
        )
        $missingManifestItems = @($requiredManifestItems | Where-Object { $manifest -notmatch [regex]::Escape($_) })
        if ($missingManifestItems.Count -gt 0) {
            Write-Host 'Android setup needs attention. These expected manifest entries were not detected:' -ForegroundColor Yellow
            $missingManifestItems | ForEach-Object { Write-Host "  - $_" -ForegroundColor Yellow }
            Write-Host 'Compare with the Android setup section in flutter/README.md. No manifest edits were made to protect your app configuration.' -ForegroundColor Yellow
        }
        else {
            Write-Status 'Required Jiva Android permissions/service appear in the manifest' 'Green'
        }
        Write-Host 'Reminder/boot notification receivers and removal of broad QUERY_ALL_PACKAGES permission are also described in flutter/README.md; review them for your generated host.' -ForegroundColor DarkYellow
    }

    if ($RepairSafe) {
        Write-Host 'Safe repair enabled: cleaning generated build artifacts and refreshing packages; source and manifest will not be changed.' -ForegroundColor Yellow
        Invoke-LoggedCommand -File 'flutter' -Arguments @('clean', '--verbose') -StepName 'Safe recovery: cleaning generated Flutter build files' -LogPath $buildLog
    }

    Invoke-LoggedCommand -File 'flutter' -Arguments @('pub', 'get', '--verbose') -StepName 'Fetching Flutter dependencies' -LogPath $buildLog

    if (-not $SkipTest) {
        Invoke-LoggedCommand -File 'flutter' -Arguments @('test', '--verbose') -StepName 'Running Flutter tests' -LogPath $buildLog
    }
    else {
        Write-Status 'Skipping tests because -SkipTest was supplied' 'Yellow'
    }

    Invoke-LoggedCommand -File 'flutter' -Arguments @('build', 'apk', '--debug', '--verbose') -StepName 'Building debug APK' -LogPath $buildLog
    $apkPath = Join-Path $FlutterDir 'build\app\outputs\flutter-apk\app-debug.apk'
    if (-not (Test-Path $apkPath)) { throw "Build reported success, but APK was not found at $apkPath" }

    if (-not $NoInstall) {
        $installArgs = @('install', '-r')
        if ($env:ANDROID_SERIAL) { $installArgs += @('-s', $env:ANDROID_SERIAL) }
        $installArgs += $apkPath
        Invoke-LoggedCommand -File 'adb' -Arguments $installArgs -StepName 'Installing APK on the connected device' -LogPath $buildLog
    }

    Write-Host ''
    Write-Host 'BUILD SUCCESS' -ForegroundColor Green
    Write-Host "APK: $apkPath" -ForegroundColor Green
    Write-Host "Verbose log: $buildLog" -ForegroundColor Green
    if ($NoInstall) { Write-Host 'Install: skipped (-NoInstall)' -ForegroundColor Yellow } else { Write-Host 'Install: completed' -ForegroundColor Green }
    exit 0
}
catch {
    $errorMessage = $_.Exception.Message
    Write-FailureReport -StepName $script:FailedStep -ErrorMessage $errorMessage -OutputLog $buildLog -FailureLog $failLog

    # Offer a narrow, reversible recovery only for build failures. Tests and app
    # source are never edited or auto-fixed because that could alter behavior.
    if ($script:FailedStep -eq 'Building debug APK' -and -not $RepairSafe -and -not $CheckOnly) {
        $choice = Read-Host 'Try safe recovery (flutter clean + pub get + one APK rebuild)? This will not edit source/manifest. Type Y to continue'
        if ($choice -match '^(Y|YES)$') {
            try {
                Invoke-LoggedCommand -File 'flutter' -Arguments @('clean', '--verbose') -StepName 'Safe recovery: cleaning generated Flutter build files' -LogPath $buildLog
                Invoke-LoggedCommand -File 'flutter' -Arguments @('pub', 'get', '--verbose') -StepName 'Safe recovery: refreshing Flutter dependencies' -LogPath $buildLog
                Invoke-LoggedCommand -File 'flutter' -Arguments @('build', 'apk', '--debug', '--verbose') -StepName 'Safe recovery: retrying APK build once' -LogPath $buildLog
                $recoveredApk = Join-Path $FlutterDir 'build\app\outputs\flutter-apk\app-debug.apk'
                if (-not (Test-Path $recoveredApk)) { throw "APK not found after recovery: $recoveredApk" }
                if (-not $NoInstall) {
                    $installArgs = @('install', '-r')
                    if ($env:ANDROID_SERIAL) { $installArgs += @('-s', $env:ANDROID_SERIAL) }
                    $installArgs += $recoveredApk
                    Invoke-LoggedCommand -File 'adb' -Arguments $installArgs -StepName 'Installing recovered APK' -LogPath $buildLog
                }
                Write-Host 'Safe recovery succeeded. No app source or manifest files were changed.' -ForegroundColor Green
                Write-Host "APK: $recoveredApk" -ForegroundColor Green
                Write-Host "Verbose log: $buildLog" -ForegroundColor Green
                exit 0
            }
            catch {
                $recoveryMessage = $_.Exception.Message
                Write-Host "Safe recovery did not resolve the problem: $recoveryMessage" -ForegroundColor Red
                Write-Host "Review the updated log and failure report: $failLog" -ForegroundColor Yellow
                exit 1
            }
        }
        Write-Host 'No automatic recovery run. Review the recommendation and logs above.' -ForegroundColor Yellow
    }
    elseif ($script:FailedStep -eq 'Running Flutter tests') {
        Write-Host 'Tests failed: no source files were changed. Fix the specific failing test/code, then rerun the build script.' -ForegroundColor Yellow
    }
    exit 1
}
