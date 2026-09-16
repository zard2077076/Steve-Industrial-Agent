param([string]$JarPath)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$work = (Resolve-Path (Join-Path $repository 'work')).Path
$root = Join-Path $work 'release-clean-room-install'
if (Test-Path -LiteralPath $root) {
    $resolved = (Resolve-Path -LiteralPath $root).Path
    if (-not $resolved.StartsWith($work + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe clean-room install cleanup target: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
$mods = New-Item -ItemType Directory -Force -Path (Join-Path $root 'instance\mods')
New-Item -ItemType Directory -Force -Path (Join-Path $root 'important-formal-root') | Out-Null
if (-not $JarPath) {
    $JarPath = Join-Path $repository 'forge-create-1.20.1\build\libs\steve-industrial-agent-0.1.0-alpha.1.jar'
}
$sourceJar = (Resolve-Path -LiteralPath $JarPath).Path
$installed = Join-Path $mods.FullName ([IO.Path]::GetFileName($sourceJar))
Copy-Item -LiteralPath $sourceJar -Destination $installed
if ((Get-FileHash $sourceJar -Algorithm SHA256).Hash -ne
        (Get-FileHash $installed -Algorithm SHA256).Hash) { throw 'CLEAN_ROOM_JAR_COPY_MISMATCH' }
if (@(Get-ChildItem $mods.FullName -File).Count -ne 1) { throw 'CLEAN_ROOM_MOD_COUNT_INVALID' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($installed)
try {
    $entries = @($archive.Entries | ForEach-Object FullName)
    foreach ($required in @(
        'dev/stevecreate/agent/forge1201/command/ReleaseInfoCommand.class',
        'dev/stevecreate/agent/forge1201/command/PilotRegionCommand.class',
        'dev/stevecreate/agent/forge1201/command/PilotDeploymentCommand.class',
        'steve-industrial-agent-release.properties')) {
        if ($required -notin $entries) { throw "CLEAN_ROOM_COMMAND_ENTRY_MISSING: $required" }
    }
} finally { $archive.Dispose() }

$bytes = [IO.File]::ReadAllBytes($installed)
$text = [Text.Encoding]::ASCII.GetString($bytes)
if ($text -match 'C:[\\/]Users[\\/]' -or $text -match '[A-Za-z]:[\\/]PCL2') {
    throw 'CLEAN_ROOM_PERSONAL_PATH_IN_JAR'
}
Write-Output "CLEAN_ROOM_INSTALL_PACKAGE PASS jarOnly=true sourceTreeRequired=false workDataRequired=false commandClasses=true personalPaths=0"
