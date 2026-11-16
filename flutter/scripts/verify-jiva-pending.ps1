# Jiva verification - run in Windows PowerShell from the Flutter project root.
# No claim of success is made until actual logs are examined.
$ErrorActionPreference = 'Continue'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logs = Join-Path (Get-Location) "jiva-verification-$stamp"
New-Item -ItemType Directory -Force -Path $logs | Out-Null
function Invoke-Check([string] $name, [string] $command) {
    Write-Host "Running $name"
    $logPath = Join-Path $logs "$name.log"
    & cmd.exe /c "$command" *> $logPath
    $code = $LASTEXITCODE
    "$name : $code" | Out-File -FilePath (Join-Path $logs 'results.txt') -Append
    if ($code -ne 0) { Write-Warning "$name failed ($code), see $logPath" }
}
Invoke-Check 'flutter-doctor' 'flutter doctor -v'
Invoke-Check 'flutter-pub-get' 'flutter pub get'
Invoke-Check 'flutter-analyze' 'flutter analyze'
Invoke-Check 'flutter-test' 'flutter test'
Invoke-Check 'flutter-build-apk' 'flutter build apk --release -v'
Invoke-Check 'adb-devices' 'adb devices -l'
Write-Host "Logs: $logs"
Write-Host 'DO NOT declare phone tested unless adb device connected and cold start, mic, overlay, screen permissions, force-stop and offline workflows were manually verified.'
