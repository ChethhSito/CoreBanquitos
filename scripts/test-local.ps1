param(
    [string]$PostgresBin = 'D:\post\bin',
    [int]$Port = 55432
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tempRoot = [IO.Path]::GetFullPath($env:TEMP)
$clusterPath = Join-Path $tempRoot ('bankcore-test-' + [Guid]::NewGuid().ToString('N'))
$pgCtl = Join-Path $PostgresBin 'pg_ctl.exe'
$initDb = Join-Path $PostgresBin 'initdb.exe'
$createDb = Join-Path $PostgresBin 'createdb.exe'
$started = $false
$testSummary = $null

foreach ($tool in @($pgCtl, $initDb, $createDb)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Missing PostgreSQL tool: $tool" }
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    $machineJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
    if ($machineJavaHome) {
        $env:JAVA_HOME = $machineJavaHome
        $env:PATH = (Join-Path $machineJavaHome 'bin') + ';' + $env:PATH
    }
}

try {
    & $initDb -D $clusterPath -U bankcore -A trust -E UTF8 --no-instructions
    if ($LASTEXITCODE -ne 0) { throw 'Temporary PostgreSQL initialization failed.' }

    $logPath = Join-Path $clusterPath 'server.log'
    & $pgCtl -D $clusterPath -o "-p $Port -h 127.0.0.1" -l $logPath -w start
    if ($LASTEXITCODE -ne 0) { throw 'Temporary PostgreSQL startup failed.' }
    $started = $true

    & $createDb -h 127.0.0.1 -p $Port -U bankcore bankcore_test
    if ($LASTEXITCODE -ne 0) { throw 'Temporary test database creation failed.' }

    $env:BANKCORE_TEST_DB_URL = "jdbc:postgresql://127.0.0.1:$Port/bankcore_test"
    $env:BANKCORE_TEST_DB_USER = 'bankcore'
    $env:BANKCORE_TEST_DB_PASSWORD = 'unused-for-temporary-cluster'
    $env:BANKCORE_JWT_SECRET = '0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF'

    Push-Location $repoRoot
    try {
        $testStartedAt = (Get-Date).ToUniversalTime()
        & (Join-Path $repoRoot 'mvnw.cmd') -q test
        if ($LASTEXITCODE -ne 0) { throw 'Integration tests failed.' }

        $reportFiles = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'target\surefire-reports') -Filter 'TEST-*.xml' |
            Where-Object { $_.LastWriteTimeUtc -ge $testStartedAt.AddSeconds(-1) })
        if ($reportFiles.Count -lt 2) { throw 'Expected integration test reports were not created.' }
        $tests = 0; $failures = 0; $errors = 0; $skipped = 0
        foreach ($reportFile in $reportFiles) {
            [xml]$report = Get-Content -LiteralPath $reportFile.FullName -Raw
            $tests += [int]$report.testsuite.tests
            $failures += [int]$report.testsuite.failures
            $errors += [int]$report.testsuite.errors
            $skipped += [int]$report.testsuite.skipped
        }
        if ($tests -lt 9 -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
            throw "Unexpected test result: $tests tests, $failures failures, $errors errors, $skipped skipped."
        }
        $testSummary = "OK: $tests tests passed, 0 failures, 0 errors, 0 skipped."
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item Env:BANKCORE_TEST_DB_URL,Env:BANKCORE_TEST_DB_USER,Env:BANKCORE_TEST_DB_PASSWORD,Env:BANKCORE_JWT_SECRET -ErrorAction SilentlyContinue
    if ($started) { & $pgCtl -D $clusterPath -m fast -w stop | Out-Null }
    if (Test-Path -LiteralPath $clusterPath) {
        $resolvedCluster = [IO.Path]::GetFullPath($clusterPath)
        if (([IO.Path]::GetDirectoryName($resolvedCluster) -ne $tempRoot) -or
            -not ([IO.Path]::GetFileName($resolvedCluster) -like 'bankcore-test-*')) {
            throw 'Refusing to remove a temporary directory outside the expected location.'
        }
        Remove-Item -LiteralPath $resolvedCluster -Recurse -Force
    }
}

Write-Host $testSummary
