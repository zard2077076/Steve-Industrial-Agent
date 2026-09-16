$ErrorActionPreference = 'Stop'

$profileTest = Join-Path $PSScriptRoot 'Test-ClientRuntimeProfile.ps1'
foreach ($profile in @('neither-client', 'create-only-client')) {
    & $profileTest -Profile $profile
}

Write-Output 'CLIENT_RUNTIME_MATRIX_VERIFIED profiles=neither-client,create-only-client'
