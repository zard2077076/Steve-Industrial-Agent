$ErrorActionPreference = 'Stop'

$profileTest = Join-Path $PSScriptRoot 'Test-RuntimeProfile.ps1'
foreach ($profile in @('neither-server', 'create-only-server')) {
    & $profileTest -Profile $profile
}

Write-Output 'RUNTIME_MATRIX_VERIFIED profiles=neither-server,create-only-server'
