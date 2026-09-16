param(
    [string]$ExpectedVersion = '0.1.0-alpha.1',
    [switch]$RequireCleanManifest
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradleProperties = Get-Content -LiteralPath (Join-Path $root 'gradle.properties') -Raw
if ($gradleProperties -notmatch "(?m)^mod_version=$([regex]::Escape($ExpectedVersion))$") {
    throw 'VERSION_GRADLE_PROPERTIES_MISMATCH'
}
$rootBuild = Get-Content -LiteralPath (Join-Path $root 'build.gradle') -Raw
if ($rootBuild -notmatch "version = '$([regex]::Escape($ExpectedVersion))'") {
    throw 'VERSION_ROOT_GRADLE_MISMATCH'
}
$jarPath = Join-Path $root "forge-create-1.20.1\build\libs\steve-industrial-agent-$ExpectedVersion.jar"
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw 'VERSION_JAR_NAME_MISMATCH' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    function Read-Entry([string]$Name) {
        $entry = $archive.GetEntry($Name)
        if (-not $entry) { throw "VERSION_ENTRY_MISSING: $Name" }
        $reader = New-Object IO.StreamReader($entry.Open())
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    }
    $manifest = Read-Entry 'META-INF/MANIFEST.MF'
    $mods = Read-Entry 'META-INF/mods.toml'
    foreach ($expected in @(
        "Implementation-Version: $ExpectedVersion",
        'Implementation-Title: Steve Industrial Agent',
        'Minecraft-Version: 1.20.1',
        'Forge-Version: 47.4.0',
        'Create-Compatibility: 6.0.6',
        'Git-Commit:',
        'Build-Timestamp:',
        'Reproducible-Build: deterministic-jar-order-and-timestamps')) {
        if (-not $manifest.Contains($expected)) { throw "VERSION_MANIFEST_MISMATCH: $expected" }
    }
    if (-not $mods.Contains(('version="' + $ExpectedVersion + '"'))) {
        throw 'VERSION_MOD_METADATA_MISMATCH'
    }
    if ($RequireCleanManifest -and -not $manifest.Contains('Dirty-Worktree: false')) {
        throw 'VERSION_MANIFEST_DIRTY'
    }
} finally { $archive.Dispose() }
Write-Output "RELEASE_VERSION PASS version=$ExpectedVersion minecraft=1.20.1 forge=47.4.0 create=6.0.6 cleanRequired=$RequireCleanManifest"
