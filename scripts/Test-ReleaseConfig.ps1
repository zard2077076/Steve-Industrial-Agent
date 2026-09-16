$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$workRoot = (Resolve-Path (Join-Path $root 'work')).Path
$fixture = Join-Path $workRoot 'release-config-fixture'
if (Test-Path -LiteralPath $fixture) {
    $resolved = (Resolve-Path -LiteralPath $fixture).Path
    if (-not $resolved.StartsWith($workRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe fixture cleanup target: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
$cli = New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'cli')
$environment = New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'environment')
$configRoot = New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'config')
$formal = New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'formal')
$configPath = Join-Path $fixture 'config.json'
@{
    instanceRoot = $configRoot.FullName
    formalRoot = $formal.FullName
    worldName = 'Config World'
} | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8

$oldInstance = $env:STEVE_INDUSTRIAL_INSTANCE_ROOT
$oldFormal = $env:STEVE_INDUSTRIAL_FORMAL_ROOT
$oldWorld = $env:STEVE_INDUSTRIAL_WORLD_NAME
try {
    $env:STEVE_INDUSTRIAL_INSTANCE_ROOT = $environment.FullName
    $env:STEVE_INDUSTRIAL_FORMAL_ROOT = $formal.FullName
    $env:STEVE_INDUSTRIAL_WORLD_NAME = 'Environment World'
    $cliResult = (& (Join-Path $PSScriptRoot 'Resolve-IndustrialAgentConfig.ps1') `
        -InstanceRoot $cli.FullName -FormalRoot $formal.FullName -WorldName 'CLI World' `
        -ConfigPath $configPath | ConvertFrom-Json)
    if ($cliResult.source -ne 'cli' -or $cliResult.worldName -ne 'CLI World') {
        throw 'CONFIG_PRECEDENCE_CLI_FAILED'
    }
    $environmentResult = (& (Join-Path $PSScriptRoot 'Resolve-IndustrialAgentConfig.ps1') `
        -ConfigPath $configPath | ConvertFrom-Json)
    if ($environmentResult.source -ne 'environment' -or $environmentResult.worldName -ne 'Environment World') {
        throw 'CONFIG_PRECEDENCE_ENVIRONMENT_FAILED'
    }
    $env:STEVE_INDUSTRIAL_INSTANCE_ROOT = $null
    $env:STEVE_INDUSTRIAL_FORMAL_ROOT = $null
    $env:STEVE_INDUSTRIAL_WORLD_NAME = $null
    $configResult = (& (Join-Path $PSScriptRoot 'Resolve-IndustrialAgentConfig.ps1') `
        -ConfigPath $configPath | ConvertFrom-Json)
    if ($configResult.source -ne 'config' -or $configResult.worldName -ne 'Config World') {
        throw 'CONFIG_PRECEDENCE_CONFIG_FAILED'
    }
    $failed = $false
    try {
        & (Join-Path $PSScriptRoot 'Resolve-IndustrialAgentConfig.ps1') `
            -InstanceRoot (Join-Path $fixture 'missing') -FormalRoot $formal.FullName `
            -WorldName 'Missing' | Out-Null
    } catch { $failed = $_.Exception.Message -like 'INSTANCE_ROOT_NOT_FOUND*' }
    if (-not $failed) { throw 'CONFIG_INVALID_PATH_REFUSAL_FAILED' }
    $failed = $false
    try {
        & (Join-Path $PSScriptRoot 'Resolve-IndustrialAgentConfig.ps1') `
            -InstanceRoot $formal.FullName -FormalRoot $formal.FullName `
            -WorldName 'Unsafe' | Out-Null
    } catch { $failed = $_.Exception.Message -like 'INSTANCE_INSIDE_FORMAL_ROOT*' }
    if (-not $failed) { throw 'CONFIG_FORMAL_ROOT_REFUSAL_FAILED' }
    Write-Output 'RELEASE_CONFIG_TEST PASS precedence=cli,environment,config invalidPath=true formalRootIsolation=true network=false telemetry=false'
} finally {
    $env:STEVE_INDUSTRIAL_INSTANCE_ROOT = $oldInstance
    $env:STEVE_INDUSTRIAL_FORMAL_ROOT = $oldFormal
    $env:STEVE_INDUSTRIAL_WORLD_NAME = $oldWorld
}
