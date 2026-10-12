$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

if (-not (Get-Command podman -ErrorAction SilentlyContinue)) {
    throw 'Podman is required to run the Testcontainers suite.'
}
& podman info --format '{{.Host.Arch}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Start the Podman machine before running tests.' }

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    $machineJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
    if ($machineJavaHome) {
        $env:JAVA_HOME = $machineJavaHome
        $env:PATH = (Join-Path $machineJavaHome 'bin') + ';' + $env:PATH
    }
}

$previousDockerHost = [Environment]::GetEnvironmentVariable('DOCKER_HOST', 'Process')
try {
    $env:DOCKER_HOST = 'npipe:////./pipe/podman-machine-default'
    Push-Location $repoRoot
    try {
        $testStartedAt = (Get-Date).ToUniversalTime()
        & (Join-Path $repoRoot 'mvnw.cmd') -q test
        if ($LASTEXITCODE -ne 0) { throw 'Integration tests failed.' }

        $reportFiles = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'target\surefire-reports') -Filter 'TEST-*.xml' |
            Where-Object { $_.LastWriteTimeUtc -ge $testStartedAt.AddSeconds(-1) })
        if ($reportFiles.Count -lt 3) { throw 'Expected integration test reports were not created.' }
        $tests = 0; $failures = 0; $errors = 0; $skipped = 0
        foreach ($reportFile in $reportFiles) {
            [xml]$report = Get-Content -LiteralPath $reportFile.FullName -Raw
            $tests += [int]$report.testsuite.tests
            $failures += [int]$report.testsuite.failures
            $errors += [int]$report.testsuite.errors
            $skipped += [int]$report.testsuite.skipped
        }
        if ($tests -lt 19 -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
            throw "Unexpected test result: $tests tests, $failures failures, $errors errors, $skipped skipped."
        }
        Write-Host "OK: $tests tests passed, 0 failures, 0 errors, 0 skipped."
    } finally {
        Pop-Location
    }
} finally {
    [Environment]::SetEnvironmentVariable('DOCKER_HOST', $previousDockerHost, 'Process')
}
