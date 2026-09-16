param([string]$JarPath)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $JarPath) {
    $jars = @(Get-ChildItem -LiteralPath (Join-Path $root 'forge-create-1.20.1\build\libs') `
        -Filter 'steve-industrial-agent-*.jar' -File | Where-Object Name -NotLike '*-sources.jar')
    if ($jars.Count -ne 1) { throw "RELEASE_JAR_COUNT_INVALID: $($jars.Count)" }
    $JarPath = $jars[0].FullName
}
$jar = (Resolve-Path -LiteralPath $JarPath).Path
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    $entries = @($archive.Entries | ForEach-Object FullName)
    $required = @('META-INF/MANIFEST.MF', 'META-INF/mods.toml',
        'META-INF/LICENSE-steve-industrial-agent', 'META-INF/NOTICE-steve-industrial-agent')
    foreach ($name in $required) {
        if ($name -notin $entries) { throw "RELEASE_JAR_REQUIRED_ENTRY_MISSING: $name" }
    }
    $deniedPrefixes = @('com/simibubi/', 'net/minecraft/', 'net/minecraftforge/',
        'net/createmod/', 'dev/engine_room/flywheel/', 'com/tterrag/registrate/',
        'journeymap/', 'saves/', 'world/', 'playerdata/', 'region/', 'logs/')
    $denied = @($entries | Where-Object {
        $entry = $_
        $deniedPrefixes | Where-Object { $entry.StartsWith($_, [StringComparison]::OrdinalIgnoreCase) }
    })
    if ($denied.Count -gt 0) { throw "RELEASE_JAR_DENYLIST_HIT: $($denied -join ', ')" }
    if (-not ($entries | Where-Object { $_ -like 'dev/stevecreate/agent/*' })) {
        throw 'RELEASE_JAR_OWN_CLASSES_MISSING'
    }
} finally { $archive.Dispose() }
Write-Output "DISTRIBUTION_BOUNDARY PASS jar=$([IO.Path]::GetFileName($jar)) entries=$($entries.Count) thirdPartyJars=0 saves=0 privateData=0"
