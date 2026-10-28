$ErrorActionPreference = 'Stop'
$blessromRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $blessromRoot
$blessromJdk = Get-ChildItem '.tools/java25' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($blessromJdk) { $env:JAVA_HOME = $blessromJdk.FullName }
if (-not $env:JAVA_HOME -or -not (Select-String "$env:JAVA_HOME/release" -Pattern '^JAVA_VERSION="25[."]' -Quiet)) { throw 'Se requiere JAVA_HOME con Java 25.' }
$env:PATH = "$env:JAVA_HOME/bin;" + $env:PATH
$blessromSecrets = Get-Content '.tools/local-secrets.json' | ConvertFrom-Json
$env:DB_PASSWORD = $blessromSecrets.DatabasePassword
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55432/blessrom_dev'
$env:TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55432/blessrom_test'
$env:MAVEN_USER_HOME = Join-Path $blessromRoot '.tools/maven-user'
$blessromMaven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
$blessromMavenPath = if ($blessromMaven) { $blessromMaven.Source } else { Join-Path $blessromRoot 'mvnw.cmd' }
& $blessromMavenPath -B -ntp '-Dmaven.repo.local=.tools/m2' verify
exit $LASTEXITCODE

