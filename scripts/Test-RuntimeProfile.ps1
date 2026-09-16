param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('neither-server', 'create-only-server')]
    [string]$Profile
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$profilesRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run\profiles'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $profilesRoot $Profile))
$logDirectory = Join-Path $root 'work\logs'

$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$profilesPrefix = $profilesRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($profilesPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    -not $runDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    $runDirectory -eq $profilesRoot) {
    throw "Refusing to use an unsafe runtime profile directory: $runDirectory"
}

# Every run starts with a new ignored world so stale saves, logs, or crash reports
# cannot make a profile pass. Only this verified repository-owned profile path is removed.
if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory | Out-Null
@(
    '# Generated for an isolated Steve Industrial Agent runtime profile.'
    'eula=true'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
@(
    '# Generated for an isolated, non-public runtime profile.'
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'generate-structures=false'
    'view-distance=3'
    'simulation-distance=3'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory ('runtime-' + $Profile + '-' + $started.ToString('yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    ("-PindustrialRuntimeProfile=$Profile") `
    :forge-create-1.20.1:runServer *>&1 | Tee-Object -FilePath $log

function Assert-LogContains([string]$Expected, [string]$Description) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $log. Expected: $Expected"
    }
}

Assert-LogContains 'java version 17.' 'Java 17 launch evidence'
Assert-LogContains 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge/Minecraft version evidence'
Assert-LogContains "RUNTIME_PROFILE_RESULT profile=$Profile mod=mekanism outcome=FAILURE code=UNSUPPORTED_RUNTIME detail=`"Optional mod is not loaded: mekanism`"" 'typed Mekanism-absent result'
Assert-LogContains 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean server shutdown evidence'

switch ($Profile) {
    'neither-server' {
        Assert-LogContains 'RUNTIME_PROFILE_RESULT profile=neither-server mod=create outcome=FAILURE code=UNSUPPORTED_RUNTIME detail="Optional mod is not loaded: create"' 'typed Create-absent result'
        Assert-LogContains 'CREATE_RUNTIME_PLAN_ABSENT PASS profile=neither-server return=0 code=REQUIRED_MOD_UNAVAILABLE worldMutation=false sessionCreated=false' 'typed Create Adapter disabled planning failure'
        Assert-LogContains 'RUNTIME_PROFILE_SMOKE PASS profile=neither-server create=FAILURE:UNSUPPORTED_RUNTIME mekanism=FAILURE:UNSUPPORTED_RUNTIME' 'neither-mod PASS marker'
        if (Select-String -LiteralPath $log -SimpleMatch 'Create 6.0.6 initializing!' -Quiet) {
            throw "Create unexpectedly initialized in neither-server profile: $log"
        }
    }
    'create-only-server' {
        Assert-LogContains 'Create 6.0.6 initializing!' 'Create 6.0.6 initialization evidence'
        Assert-LogContains 'RUNTIME_PROFILE_RESULT profile=create-only-server mod=create outcome=SUCCESS components=' 'typed Create success result'
        Assert-LogContains 'RUNTIME_PROFILE_SMOKE PASS profile=create-only-server create=SUCCESS mekanism=FAILURE:UNSUPPORTED_RUNTIME' 'Create-only PASS marker'
    }
}

$newCrashReports = @()
$crashDirectory = Join-Path $runDirectory 'crash-reports'
if (Test-Path -LiteralPath $crashDirectory) {
    $newCrashReports = @(Get-ChildItem -LiteralPath $crashDirectory -File |
        Where-Object LastWriteTime -ge $started)
}
if ($newCrashReports.Count -gt 0) {
    throw "Runtime profile created crash report(s): $($newCrashReports.FullName -join ', ')"
}

$fatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]',
    'Exception in thread',
    'RUNTIME_PROFILE_SMOKE FAIL',
    'Preparing crash report'
)
$fatalMatches = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatalMatches.Count -gt 0) {
    throw "Runtime profile log contains fatal/error evidence: $($fatalMatches.Line -join ' | ')"
}

Write-Output "RUNTIME_PROFILE_VERIFIED profile=$Profile java=17 minecraft=1.20.1 forge=47.4.10 crashReports=0"
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
