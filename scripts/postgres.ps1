param([string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin', [int]$Port = 55432)
$ErrorActionPreference = 'Stop'
$blessromRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $blessromRoot
if (-not (Test-Path "$PostgresBin\postgres.exe")) { throw 'Indica -PostgresBin con una instalación de PostgreSQL 17.' }
New-Item -ItemType Directory -Force '.tools' | Out-Null
$blessromSecretsPath = Join-Path $blessromRoot '.tools/local-secrets.json'
if (-not (Test-Path $blessromSecretsPath)) {
    $blessromSecrets = @{
        DatabasePassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
        AdminPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    }
    $blessromSecrets | ConvertTo-Json | Set-Content $blessromSecretsPath -Encoding utf8
}
$blessromSecrets = Get-Content $blessromSecretsPath | ConvertFrom-Json
if (-not (Test-Path '.tools/postgres-data/PG_VERSION')) {
    $blessromPasswordFile = Join-Path $blessromRoot '.tools/postgres-password'
    try {
        [IO.File]::WriteAllText($blessromPasswordFile, $blessromSecrets.DatabasePassword)
        & "$PostgresBin\initdb.exe" -D '.tools/postgres-data' -U blessrom "--pwfile=$blessromPasswordFile" --auth-host=scram-sha-256 --auth-local=scram-sha-256 --encoding=UTF8 --locale=C
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo inicializar PostgreSQL.' }
    } finally { if (Test-Path $blessromPasswordFile) { Remove-Item -LiteralPath $blessromPasswordFile } }
}
# Proceso en primer plano: Ctrl+C detiene solamente esta instancia local.
& "$PostgresBin\postgres.exe" -D '.tools/postgres-data' -h 127.0.0.1 -p $Port -c shared_buffers=64MB -c max_connections=30
exit $LASTEXITCODE

