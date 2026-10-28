param([ValidateRange(1024,65535)][int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$blessromRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $blessromRoot
$blessromSecretsFile = Join-Path $blessromRoot '.tools/local-secrets.json'
if (Test-Path $blessromSecretsFile) {
    $blessromSecrets = Get-Content $blessromSecretsFile | ConvertFrom-Json
    if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = $blessromSecrets.DatabasePassword }
    if (-not $env:BLESSROM_ADMIN_PASSWORD) { $env:BLESSROM_ADMIN_PASSWORD = $blessromSecrets.AdminPassword }
}
if (-not $env:DB_URL) { $env:DB_URL = 'jdbc:postgresql://127.0.0.1:55432/blessrom_dev' }
$blessromLocalJdk = Get-ChildItem -LiteralPath (Join-Path $blessromRoot '.tools\java25') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($blessromLocalJdk) {
    $env:JAVA_HOME = $blessromLocalJdk.FullName
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME\release")) {
    throw 'Configura JAVA_HOME con un JDK 25 antes de iniciar.'
}
if (-not (Select-String -LiteralPath "$env:JAVA_HOME\release" -Pattern '^JAVA_VERSION="25[."]' -Quiet)) {
    throw 'El backend requiere Java 25. Revisa JAVA_HOME.'
}
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
$env:MAVEN_USER_HOME = Join-Path $blessromRoot '.tools\maven-user'
$blessromMaven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
$blessromMavenPath = if ($blessromMaven) { $blessromMaven.Source } else { Join-Path $blessromRoot 'mvnw.cmd' }
& $blessromMavenPath '-B' '-ntp' '-Dquarkus.analytics.disabled=true' '-Dmaven.repo.local=.tools/m2' 'quarkus:dev' "-Dquarkus.http.port=$Port" '-Dquarkus.http.host=127.0.0.1' '-Ddebug=false'
exit $LASTEXITCODE

