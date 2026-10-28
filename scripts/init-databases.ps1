param([string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin', [int]$Port = 55432)
$ErrorActionPreference = 'Stop'
$blessromRoot = Split-Path $PSScriptRoot -Parent
$blessromSecrets = Get-Content (Join-Path $blessromRoot '.tools/local-secrets.json') | ConvertFrom-Json
$env:PGPASSWORD = $blessromSecrets.DatabasePassword
try {
    foreach ($blessromDatabase in @('blessrom_dev','blessrom_test')) {
        $blessromExists = & "$PostgresBin\psql.exe" -h 127.0.0.1 -p $Port -U blessrom -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$blessromDatabase'"
        if ($LASTEXITCODE -ne 0) { throw 'Inicia primero scripts/postgres.ps1.' }
        if ($blessromExists -ne '1') {
            & "$PostgresBin\createdb.exe" -h 127.0.0.1 -p $Port -U blessrom $blessromDatabase
            if ($LASTEXITCODE -ne 0) { throw "No se pudo crear $blessromDatabase." }
        }
    }
} finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }

