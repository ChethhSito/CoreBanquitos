$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envFile = Join-Path $repoRoot '.env'

if (-not (Test-Path -LiteralPath $envFile)) {
    throw 'Falta .env. Ejecuta primero .\scripts\setup-local-db.ps1.'
}

foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^\s*(#|$)') { continue }
    $parts = $line -split '=', 2
    if ($parts.Count -ne 2) { throw 'Formato invalido en .env.' }
    [Environment]::SetEnvironmentVariable($parts[0], $parts[1], 'Process')
}

if (-not $env:BANKCORE_JWT_SECRET) {
    $secretBytes = New-Object byte[] 32
    $randomGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $randomGenerator.GetBytes($secretBytes)
    } finally {
        $randomGenerator.Dispose()
    }
    $env:BANKCORE_JWT_SECRET = [BitConverter]::ToString($secretBytes).Replace('-', '')
    Add-Content -LiteralPath $envFile -Value "BANKCORE_JWT_SECRET=$env:BANKCORE_JWT_SECRET" -Encoding utf8
}

$env:SPRING_PROFILES_ACTIVE = 'local'

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    $machineJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
    if ($machineJavaHome) {
        $env:JAVA_HOME = $machineJavaHome
        $env:PATH = (Join-Path $machineJavaHome 'bin') + ';' + $env:PATH
    }
}

Push-Location $repoRoot
try {
    & (Join-Path $repoRoot 'mvnw.cmd') spring-boot:run
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
