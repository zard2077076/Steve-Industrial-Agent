param(
    [string]$InstanceRoot,
    [string]$WorldName,
    [string]$FormalRoot,
    [string]$ConfigPath,
    [switch]$Validate,
    [switch]$Diagnostics
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Read-Config([string]$Path) {
    if (-not $Path) { return $null }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "CONFIG_NOT_FOUND: $Path"
    }
    try { return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }
    catch { throw "CONFIG_INVALID_JSON: $Path" }
}

function Resolve-SafePath([string]$Value, [string]$Name) {
    if (-not $Value) { throw "$($Name.ToUpperInvariant())_NOT_CONFIGURED" }
    if ($Value.IndexOf([char]0) -ge 0) { throw "$($Name.ToUpperInvariant())_INVALID: NUL" }
    $full = [IO.Path]::GetFullPath($Value)
    if (-not (Test-Path -LiteralPath $full -PathType Container)) {
        throw "$($Name.ToUpperInvariant())_NOT_FOUND: $full"
    }
    $current = Get-Item -LiteralPath $full -Force
    while ($null -ne $current) {
        if (($current.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$($Name.ToUpperInvariant())_REPARSE_REFUSED: $($current.FullName)"
        }
        $current = $current.Parent
    }
    return (Resolve-Path -LiteralPath $full).Path
}

function Discover-Instance {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:APPDATA) { $candidates.Add((Join-Path $env:APPDATA '.minecraft')) }
    if ($env:LOCALAPPDATA) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA 'Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\game\.minecraft'))
    }
    $valid = @($candidates | Where-Object {
        Test-Path -LiteralPath $_ -PathType Container
    } | Select-Object -Unique)
    if ($valid.Count -eq 1) { return $valid[0] }
    if ($valid.Count -gt 1) { throw 'INSTANCE_DISCOVERY_AMBIGUOUS: use CLI, environment or config file' }
    return $null
}

$defaultConfig = Join-Path ([Environment]::GetFolderPath('ApplicationData')) 'SteveIndustrialAgent\config.json'
if (-not $ConfigPath -and (Test-Path -LiteralPath $defaultConfig -PathType Leaf)) {
    $ConfigPath = $defaultConfig
}
$config = Read-Config $ConfigPath
$resolvedSource = 'cli'

if (-not $InstanceRoot) {
    if ($env:STEVE_INDUSTRIAL_INSTANCE_ROOT) {
        $InstanceRoot = $env:STEVE_INDUSTRIAL_INSTANCE_ROOT; $resolvedSource = 'environment'
    } elseif ($config -and $config.instanceRoot) {
        $InstanceRoot = [string]$config.instanceRoot; $resolvedSource = 'config'
    } else {
        $InstanceRoot = Discover-Instance; $resolvedSource = 'safe-discovery'
    }
}
if (-not $WorldName) {
    if ($env:STEVE_INDUSTRIAL_WORLD_NAME) { $WorldName = $env:STEVE_INDUSTRIAL_WORLD_NAME }
    elseif ($config -and $config.worldName) { $WorldName = [string]$config.worldName }
}
if (-not $FormalRoot) {
    if ($env:STEVE_INDUSTRIAL_FORMAL_ROOT) { $FormalRoot = $env:STEVE_INDUSTRIAL_FORMAL_ROOT }
    elseif ($config -and $config.formalRoot) { $FormalRoot = [string]$config.formalRoot }
}
if (-not $WorldName -or $WorldName -match '[\\/:*?"<>|]' -or $WorldName -in @('.', '..')) {
    throw 'WORLD_NAME_INVALID: configure one exact in-game world identity without path separators'
}

$instance = Resolve-SafePath $InstanceRoot 'instance_root'
$formal = Resolve-SafePath $FormalRoot 'formal_root'
if ($instance.StartsWith($formal + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        $instance.Equals($formal, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'INSTANCE_INSIDE_FORMAL_ROOT: disposable instance must be outside every important/formal root'
}
$world = Join-Path (Join-Path $instance 'saves') $WorldName
$result = [ordered]@{
    schema = 'steve-industrial-agent-config/v1'
    precedence = 'CLI > environment > config file > safe discovery > typed failure'
    source = $resolvedSource
    instanceRoot = $instance
    formalRoot = $formal
    worldName = $WorldName
    worldPath = [IO.Path]::GetFullPath($world)
    worldExists = (Test-Path -LiteralPath $world -PathType Container)
    disposableWorldRequired = $true
    networkEnabled = $false
    telemetryEnabled = $false
}

if ($Validate) { Write-Output 'CONFIG_VALID' }
if ($Diagnostics) {
    Write-Output "CONFIG_DIAGNOSTICS source=$resolvedSource worldExists=$($result.worldExists) network=false telemetry=false"
}
$result | ConvertTo-Json -Depth 4
