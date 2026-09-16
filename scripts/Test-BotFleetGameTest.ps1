$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $runRoot 'bot-fleet-gametest'))
$logDirectory = Join-Path $root 'work\logs'
$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not $runDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        $runDirectory.StartsWith('D:\PCL2\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing unsafe Bot GameTest directory: $runDirectory"
}

$preexisting = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'java.exe' -and $_.CommandLine -match '(Minecraft|forge|runGameTestServer|server\.jar)'
})
if ($preexisting.Count -gt 0) {
    throw "Bot GameTest slot is not free; Java process(es): $($preexisting.ProcessId -join ',')"
}

if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory | Out-Null
@('# Repository-owned isolated Bot Fleet GameTest.', 'eula=true') |
    Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
'steve-industrial:isolated-bot/v1' |
    Set-Content -LiteralPath (Join-Path $runDirectory '.steve-industrial-bot-test') -Encoding ascii
@(
    '# Repository-owned isolated Bot Fleet GameTest.'
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'level-seed=steve-industrial-bot-fleet-v1'
    'level-type=minecraft:flat'
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    'generate-structures=false'
    'view-distance=6'
    'simulation-distance=6'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory ('bot-fleet-gametest-' + $started.ToString('yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    -PbotFleetGameTest `
    :forge-create-1.20.1:runGameTestServer *>&1 | Tee-Object -FilePath $log

function Assert-LogContains([string]$Expected, [string]$Description) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $log. Expected: $Expected"
    }
}

Assert-LogContains 'java version 17.' 'Java 17 evidence'
Assert-LogContains 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge version evidence'
Assert-LogContains 'BOT_PHYSICAL_ACTION_CHAIN PASS spawned=true identityStable=true navigate=true fetch=2 carry=true transport=true place=true verify=true safeMachineFace=true removeSessionOwned=true returnLeftover=1 idleReload=true reloadDriftRefused=true duplicateWithdrawal=false playerInventoryReads=0 privateContainerReads=0 existingEntity=true smoothAdjacentMovement=true teleport=false evidence=7' 'physical Bot action chain'
Assert-LogContains 'BOT_POLICY_GAMETEST PASS adjacentMovement=true repath=true cancel=true idleAfterCancel=true outOfRegionRefused=true authorityRevoked=true worldMutationAfterRevoke=false attackActions=0 unknownRightClicks=0 teleportBypass=false' 'Bot policy failure matrix'
Assert-LogContains 'BOT_FLEET_PHYSICAL_DAG PASS workers=2 maxActive=2 parallelAssignments=true parallelMovement=true parallelFetch=true parallelTransport=true sequentialDependencies=true workReservations=2 placements=2 physicalSources=2 completedTasks=6 existingEntity=true smoothAdjacentMovement=true teleport=false cleanup=true terminalFailures=0' 'two-Bot physical DAG'
foreach ($workers in @(3, 5)) {
    Assert-LogContains (
        'BOT_FLEET_SCALED_' + $workers + ' PASS workers=' + $workers +
        ' maxActive=' + $workers +
        ' existingEntity=true sharedKernel=GraphNeutralFleetCoordinator ' +
        'parallelAssignments=true allMoved=true botOverlap=false physicalSources=' +
        $workers + ' placements=' + $workers + ' completedTasks=' +
        ($workers * 3) + ' leases=' + $workers +
        ' teleport=false playerInventoryReads=0 privateContainerReads=0 ' +
        'cleanup=true terminalFailures=0') (
            $workers.ToString() + '-Bot shared fleet physical DAG')
}
Assert-LogContains 'All 5 required tests passed' 'five Bot GameTest successes'
Assert-LogContains 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean shutdown'

$crashDirectory = Join-Path $runDirectory 'crash-reports'
$newCrashes = if (Test-Path -LiteralPath $crashDirectory) {
    @(Get-ChildItem -LiteralPath $crashDirectory -File | Where-Object LastWriteTime -ge $started)
} else { @() }
if ($newCrashes.Count -gt 0) {
    throw "Bot GameTest created crash report(s): $($newCrashes.FullName -join ', ')"
}
$fatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]'
    'Exception in thread'
    'Preparing crash report'
    'Bot task failed'
    'test failed'
    'Game test failed'
)
$fatal = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatal.Count -gt 0) {
    throw "Bot GameTest log contains fatal evidence: $($fatal.Line -join ' | ')"
}
$residual = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'java.exe' -and $_.CommandLine -like "*$runDirectory*"
})
if ($residual.Count -gt 0) {
    throw "Bot GameTest left Java process(es): $($residual.ProcessId -join ',')"
}

Write-Output 'BOT_FLEET_GAMETEST_VERIFIED tests=5 existingConstructionBotEntity=true workers2=true workers3=true workers5=true sharedKernel=true adjacentPath=true smoothMovement=true teleport=false repath=true fetch=true carry=true parallelTransport=true place=true verify=true botOverlap=false safeInteraction=true cleanup=true return=true idleReload=true cancel=true regionGuard=true authorityRevocation=true playerInventoryReads=0 privateContainerReads=0 crashReports=0 residualProcesses=0'
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
