param([Parameter(Mandatory=$true)][string]$Root)
$ErrorActionPreference = 'Stop'
$jar = Join-Path $Root 'gradle\wrapper\gradle-wrapper.jar'
$expected = '498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17'
if (!(Test-Path -LiteralPath $jar)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $jar) | Out-Null
    $tmp = "$jar.download.$PID"
    try {
        Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar' -OutFile $tmp
        $actual = (Get-FileHash -LiteralPath $tmp -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $expected) { throw 'Wrapper SHA-256 mismatch. Refusing untrusted download.' }
        Move-Item -LiteralPath $tmp -Destination $jar -Force
    } finally {
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }
    }
}
