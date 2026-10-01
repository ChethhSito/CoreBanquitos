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

    Push-Location $repoRoot
    try {
        $testStartedAt = (Get-Date).ToUniversalTime()
        & (Join-Path $repoRoot 'mvnw.cmd') -q test
        if ($LASTEXITCODE -ne 0) { throw 'Integration tests failed.' }

        $reportPath = Join-Path $repoRoot 'target\surefire-reports\TEST-com.chethhsito.bankcore.transfer.TransferServiceIntegrationTest.xml'
        if (-not (Test-Path -LiteralPath $reportPath)) {
            throw 'The transfer test report was not created.'
        }
        $reportFile = Get-Item -LiteralPath $reportPath
        if ($reportFile.LastWriteTimeUtc -lt $testStartedAt.AddSeconds(-1)) {
            throw 'The transfer test report was not updated by this run.'
        }
        [xml]$report = Get-Content -LiteralPath $reportPath -Raw
        $tests = [int]$report.testsuite.tests
        $failures = [int]$report.testsuite.failures
        $errors = [int]$report.testsuite.errors
        $skipped = [int]$report.testsuite.skipped
        if ($tests -lt 4 -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
            throw "Unexpected test result: $tests tests, $failures failures, $errors errors, $skipped skipped."
        }
        $testSummary = "OK: $tests tests passed, 0 failures, 0 errors, 0 skipped."
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item Env:BANKCORE_TEST_DB_URL,Env:BANKCORE_TEST_DB_USER,Env:BANKCORE_TEST_DB_PASSWORD -ErrorAction SilentlyContinue
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
