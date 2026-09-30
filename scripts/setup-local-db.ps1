param(
    [string]$PsqlPath = 'D:\post\bin\psql.exe'
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envFile = Join-Path $repoRoot '.env'

if (-not (Test-Path -LiteralPath $PsqlPath)) {
    $command = Get-Command psql -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw 'No encontré psql. Indica su ruta con -PsqlPath.'
    }
    $PsqlPath = $command.Source
}

if (Test-Path -LiteralPath $envFile) {
    throw 'Ya existe .env. No se cambiará la contraseña local automáticamente.'
}

$securePassword = Read-Host 'Contraseña local del usuario postgres' -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $env:PGPASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
}

try {
    $appPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))

    $databaseExists = & $PsqlPath -X -w -h 127.0.0.1 -U postgres -d postgres -tA -c "SELECT 1 FROM pg_database WHERE datname = 'bankcore'"
    if ($LASTEXITCODE -ne 0) { throw 'No pude consultar las bases de datos.' }
    if ($databaseExists -eq '1') {
        throw 'La base bankcore ya existe. Revisa su propietario antes de continuar.'
    }

    $roleExists = & $PsqlPath -X -w -h 127.0.0.1 -U postgres -d postgres -tA -c "SELECT 1 FROM pg_roles WHERE rolname = 'bankcore'"
    if ($LASTEXITCODE -ne 0) { throw 'No pude autenticar como postgres.' }

    if ($roleExists -eq '1') {
        $roleSql = "ALTER ROLE bankcore WITH LOGIN PASSWORD '$appPassword';"
    } else {
        $roleSql = "CREATE ROLE bankcore WITH LOGIN PASSWORD '$appPassword';"
    }
    $roleSql | & $PsqlPath -X -w -v ON_ERROR_STOP=1 -h 127.0.0.1 -U postgres -d postgres -f -
    if ($LASTEXITCODE -ne 0) { throw 'No pude configurar el usuario bankcore.' }

    'CREATE DATABASE bankcore OWNER bankcore;' | & $PsqlPath -X -w -v ON_ERROR_STOP=1 -h 127.0.0.1 -U postgres -d postgres -f -
    if ($LASTEXITCODE -ne 0) { throw 'No pude crear la base bankcore.' }

    @(
        'BANKCORE_DB_URL=jdbc:postgresql://127.0.0.1:5432/bankcore'
        'BANKCORE_DB_USER=bankcore'
        "BANKCORE_DB_PASSWORD=$appPassword"
    ) | Set-Content -LiteralPath $envFile -Encoding utf8

    Write-Host 'Base bankcore y usuario de aplicación listos. Las credenciales locales están en .env (ignorado por Git).'
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    $appPassword = $null
}
