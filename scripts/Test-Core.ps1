$ErrorActionPreference = 'Stop'
$gradleArgs = @('-PskipGameModules=true', 'clean', ':core:test', ':adapter-api:test')
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$logDirectory = Join-Path $root 'work\logs'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$log = Join-Path $logDirectory ('core-test-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') @gradleArgs *>&1 | Tee-Object -FilePath $log
Write-Output "Test log: $log"
