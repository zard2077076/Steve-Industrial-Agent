$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') :forge-create-1.20.1:runClient
