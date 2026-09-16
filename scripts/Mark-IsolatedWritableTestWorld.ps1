param(
    [string]$InstanceRoot,
    [string]$ExpectedWorldName = 'Steve Agent Test'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$local = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $repoRoot 'local.properties')) {
    if ($line -match '^\s*([^#][^=]*)=(.*)$') { $local[$Matches[1].Trim()] = $Matches[2].Trim() }
}
$pclRoot = if ($local.ContainsKey('pcl2_root')) { [IO.Path]::GetFullPath($local['pcl2_root']) } `
        elseif ($env:PCL2_ROOT) { [IO.Path]::GetFullPath($env:PCL2_ROOT) } else { '' }
if ([string]::IsNullOrWhiteSpace($InstanceRoot)) {
    $InstanceRoot = Join-Path $repoRoot 'work\isolated-player\SteveAgent_DeceasedCraft_Test'
}
$instance = (Resolve-Path -LiteralPath $InstanceRoot).Path
$run = (Resolve-Path -LiteralPath (Join-Path $instance 'run')).Path
$allowed = [IO.Path]::GetFullPath((Join-Path $repoRoot 'work\isolated-player')).TrimEnd('\') + '\'
if (-not $run.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or
        (-not [string]::IsNullOrWhiteSpace($pclRoot) -and
                $run.StartsWith($pclRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase))) {
    throw 'TEST_WORLD_IDENTITY_MISMATCH: gameDir is outside the repository-owned isolated instance.'
}
$running = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -like "*$run*"
})
if ($running.Count -gt 0) {
    throw "TEST_WORLD_IN_USE_DURING_BACKUP: Minecraft is still using the test instance (PID $($running.ProcessId -join ','))."
}
$saves = Join-Path $run 'saves'
$worlds = @(Get-ChildItem -LiteralPath $saves -Directory -ErrorAction Stop | Where-Object {
    Test-Path -LiteralPath (Join-Path $_.FullName 'level.dat') -PathType Leaf
})
if ($worlds.Count -eq 0) { throw 'TEST_WORLD_NOT_FOUND: create Steve Agent Test, enter once, save, exit, and fully close Minecraft.' }
if ($worlds.Count -gt 1) { throw "MULTIPLE_TEST_WORLDS_AMBIGUOUS: $($worlds.Name -join ', ')" }
if ($worlds[0].Name -ne $ExpectedWorldName) {
    throw "TEST_WORLD_IDENTITY_MISMATCH: expected directory '$ExpectedWorldName', found '$($worlds[0].Name)'."
}
'ISOLATED_WRITABLE_TEST_WORLD' |
        Set-Content -LiteralPath (Join-Path $worlds[0].FullName '.steve-industrial-writable-test-world') -Encoding ascii
$levelHash = (Get-FileHash -LiteralPath (Join-Path $worlds[0].FullName 'level.dat') -Algorithm SHA256).Hash.ToLowerInvariant()
$evidence = [ordered]@{
    schema = 'steve-industrial:iwp-world-marker/v1'
    worldName = $worlds[0].Name
    canonicalWorldPath = $worlds[0].FullName
    levelDatSha256 = $levelHash
    marker = 'ISOLATED_WRITABLE_TEST_WORLD'
    markedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    minecraftProcessCount = 0
    formalWorldTouched = $false
}
$evidencePath = Join-Path $instance 'evidence\world-marker.json'
$evidence | ConvertTo-Json | Set-Content -LiteralPath $evidencePath -Encoding utf8
Write-Output "IWP_WORLD_MARKED world=$($worlds[0].FullName) levelDatSha256=$levelHash processCount=0 formalWorldTouched=false evidence=$evidencePath"
