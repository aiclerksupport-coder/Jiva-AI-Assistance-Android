# Run from any directory on Windows with Flutter + Android toolchain installed.
# Does NOT install an APK or change a connected device.
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $project
$logs = Join-Path $project 'verification-logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$steps = @(
  @{ name='pub-get'; args=@('pub','get') },
  @{ name='analyze'; args=@('analyze') },
  @{ name='test'; args=@('test') },
  @{ name='release-build'; args=@('build','apk','--release') }
)
foreach ($step in $steps) {
  $log = Join-Path $logs ($step.name + '.log')
  Write-Host ('Checking: ' + $step.name)
  $arguments = $step.args
  & flutter @arguments *>&1 | Tee-Object -FilePath $log
  if ($LASTEXITCODE -ne 0) {
    throw ('FAILED ' + $step.name + ' (exit ' + $LASTEXITCODE + '). See ' + $log)
  }
}
Write-Host 'All commands exited 0. Real-device Accessibility tests still required.'
