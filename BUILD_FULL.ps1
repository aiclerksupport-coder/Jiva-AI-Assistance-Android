<#
Jiva AI one-command Windows build + install (PowerShell 5.1+).
Run at extracted source root:
  powershell -NoProfile -ExecutionPolicy Bypass -File ".\BUILD_FULL.ps1"
All command output appears on screen; only a failed step gets a permanent log.
Run only one build at a time. Automatic dart format changes source files.
#>
[CmdletBinding()]
param(
  [switch]$AllowIncompleteTrial,
  [string]$DeviceSerial = '',
  [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$flutterRoot = Join-Path $root 'flutter'
$logsDir = Join-Path $root 'BUILD_FAILURE_LOGS'
$temporary = Join-Path ([System.IO.Path]::GetTempPath()) ('jiva_build_' + [guid]::NewGuid().ToString('N'))
$failed = $false
$script:stage = ''
New-Item -ItemType Directory -Path $temporary -Force | Out-Null
$start = Get-Date
function Fail-Stage([string]$Reason) {
  $script:failed = $true
  New-Item -ItemType Directory -Path $logsDir -Force | Out-Null
  $stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
  $safeName = ($script:stage -replace '[^a-zA-Z0-9_-]', '_')
  if (-not $safeName) { $safeName = 'preflight' }
  $logFile = Join-Path $logsDir ("FAILED_${stamp}_${safeName}.log")
  $captured = Join-Path $temporary 'stage-output.log'
  $header = "FAILED STAGE: $($script:stage)`r`nDATE: $(Get-Date -Format o)`r`nREASON: $Reason`r`nPROJECT: $root`r`n`r`n"
  [System.IO.File]::WriteAllText($logFile, $header, [System.Text.UTF8Encoding]::new($false))
  if (Test-Path -LiteralPath $captured) { Get-Content -LiteralPath $captured | Add-Content -LiteralPath $logFile -Encoding UTF8 }
  Write-Host "`nBUILD STOPPED: $Reason" -ForegroundColor Red
  Write-Host "FAILURE LOG: $logFile" -ForegroundColor Yellow
  throw $Reason
}
function Run-Step([string]$Name,[string]$Exe,[string[]]$Arguments) {
  $script:stage = $Name
  $stageLog = Join-Path $temporary 'stage-output.log'
  if (Test-Path -LiteralPath $stageLog) { Remove-Item -LiteralPath $stageLog -Force }
  Write-Host "`n===== $Name =====" -ForegroundColor Cyan
  Write-Host ("COMMAND: " + $Exe + ' ' + ($Arguments -join ' ')) -ForegroundColor DarkGray
  # Use external process directly so output is streamed on screen AND captured.
  # PowerShell 5.1 may treat native stderr as nonterminating errors, so do not use ErrorAction Stop here.
  try {
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $Exe @Arguments 2>&1 | ForEach-Object {
      $line = [string]$_
      Write-Host $line
      Add-Content -LiteralPath $stageLog -Value $line -Encoding UTF8
    }
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $oldPreference
  } catch {
    $ErrorActionPreference = 'Stop'
    Add-Content -LiteralPath $stageLog -Value ([string]$_) -Encoding UTF8
    Fail-Stage "Could not run $Name : $_"
  }
  if ($null -eq $exit -or $exit -ne 0) { Fail-Stage "$Name exited with code $exit" }
  Write-Host "PASS: $Name" -ForegroundColor Green
}
function Find-Command([string]$Executable) {
  $item = Get-Command $Executable -ErrorAction SilentlyContinue
  if ($null -eq $item) { $script:stage = 'preflight'; Fail-Stage "$Executable not found in PATH" }
  return $item.Source
}
try {
  $script:stage = 'preflight'
  if (-not (Test-Path -LiteralPath (Join-Path $flutterRoot 'pubspec.yaml'))) { Fail-Stage 'flutter/pubspec.yaml missing. Run script from full extracted Jiva source.' }
  $flutter = Find-Command 'flutter'
  $dart = Find-Command 'dart'
  $adb = Find-Command 'adb'
  Write-Host 'JIVA AI - COMPLETE FLUTTER BUILD + PHONE INSTALL' -ForegroundColor Magenta
  Write-Host 'NOTE: Current Android Gradle setup signs release with DEBUG key. NOT for Play Store distribution.' -ForegroundColor Yellow
  Write-Host 'NOTE: dart format --output=write will update Dart source formatting.' -ForegroundColor Yellow
  Push-Location $flutterRoot
  try {
    Run-Step '01_flutter_doctor' $flutter @('doctor','-v')
    Run-Step '02_flutter_clean' $flutter @('clean','-v')
    $formatTargets = @('lib','test','packages/jiva_accessibility/lib')
    if (Test-Path -LiteralPath 'packages/jiva_accessibility/test') { $formatTargets += 'packages/jiva_accessibility/test' }
    Run-Step '03_dart_format' $dart (@('format','--output=write') + $formatTargets)
    Run-Step '04_flutter_pub_get' $flutter @('pub','get','-v')
    Run-Step '05_flutter_analyze' $flutter @('analyze','--no-pub')
    Run-Step '06_flutter_test' $flutter @('test','--no-pub','--reporter','expanded')
    $wrapper = Join-Path $flutterRoot 'android\gradle\wrapper\gradle-wrapper.jar'
    if (-not (Test-Path -LiteralPath $wrapper)) {
      Run-Step '07_gradle_wrapper' 'powershell.exe' @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $flutterRoot 'android\scripts\ensure-gradle-wrapper.ps1'),'-Root',(Join-Path $flutterRoot 'android'))
    }
    Run-Step '08_release_apk' $flutter @('build','apk','--release','--verbose','--no-pub')
    $apk = Join-Path $flutterRoot 'build\app\outputs\flutter-apk\app-release.apk'
    $script:stage = '09_verify_apk'
    if (-not (Test-Path -LiteralPath $apk)) { Fail-Stage "Release APK missing: $apk" }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash
    Write-Host "APK: $apk" -ForegroundColor Green
    Write-Host "SHA256: $hash" -ForegroundColor Green
    if (-not $SkipInstall) {
      Run-Step '10_adb_devices' $adb @('devices','-l')
      $script:stage = '11_check_device'
      $raw = & $adb devices
      $devices = @($raw | Where-Object { $_ -match '^([^\s]+)\s+device\s*$' } | ForEach-Object { $Matches[1] })
      if ($DeviceSerial) {
        if ($devices -notcontains $DeviceSerial) { Fail-Stage "Selected device $DeviceSerial is not connected and authorized" }
        $targetSerial = $DeviceSerial
      } else {
        if ($devices.Count -ne 1) { Fail-Stage "Exactly one authorized Android device is required; found $($devices.Count). Connect one phone or pass -DeviceSerial SERIAL." }
        $targetSerial = $devices[0]
      }
      Run-Step '12_adb_install' $adb @('-s',$targetSerial,'install','-r',$apk)
      Write-Host "INSTALL SUCCESS: $targetSerial" -ForegroundColor Green
    }
    Write-Host "`nJIVA BUILD SUCCESS. APK: $apk" -ForegroundColor Green
    Write-Host "TOTAL DURATION: $((Get-Date) - $start)" -ForegroundColor Green
  } finally { Pop-Location }
} catch {
  if (-not $script:failed) { Fail-Stage ([string]$_) }
  exit 1
} finally {
  if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Recurse -Force -ErrorAction SilentlyContinue }
}
