param(
    [string]$Pcl2Root = 'D:\PCL2',
    [string]$SourceInstanceName = 'DeceasedCraft_Beta 5.10.16',
    [string]$TargetInstanceName = 'SteveAgent_RC_DeceasedCraft_Beta_5.10.16',
    [string]$TestDataRoot = 'D:\SteveAgent-RC-PCL2-TestData'
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$version = '0.1.0-alpha.1'
$worldName = 'A01'
$pcl = [IO.Path]::GetFullPath($Pcl2Root).TrimEnd('\')
$minecraft = Join-Path $pcl '.minecraft'
$source = Join-Path $minecraft "versions\$SourceInstanceName"
$target = Join-Path $minecraft "versions\$TargetInstanceName"
$testData = [IO.Path]::GetFullPath($TestDataRoot).TrimEnd('\')
$backupRoot = Join-Path $testData 'backups'
$evidenceRoot = Join-Path $testData 'evidence'
$toolsRoot = Join-Path $testData 'tools'
$releaseJar = Join-Path $repository "dist\v$version\steve-industrial-agent-$version.jar"
$helper = Join-Path $repository 'tools\Complete-IndustrialAgentBackup.ps1'

$copyScopes = @(
    'mods', 'config', 'defaultconfigs', 'kubejs', 'generated_datapacks',
    'customnpcs', 'patchouli_books', 'tacz', 'fancymenu_data', 'resourcepacks'
)
$forbiddenRootNames = @(
    'saves', 'logs', 'crash-reports', 'screenshots', 'backups', 'schematics',
    'xaero', 'XaeroWaypoints_BACKUP240807', '.mixin.out', '.lazyyyyy',
    'options.txt', 'optionsof.txt', 'servers.dat', 'servers.dat_old',
    'usercache.json', 'usernamecache.json'
)

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-SafeTarget([string]$Path, [string]$Parent) {
    $pathValue = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $parentValue = [IO.Path]::GetFullPath($Parent).TrimEnd('\')
    if (-not $pathValue.StartsWith($parentValue + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "PCL2_ACCEPTANCE_UNSAFE_TARGET: $pathValue"
    }
}

if (-not (Test-Path -LiteralPath $pcl -PathType Container)) {
    throw "PCL2_ROOT_NOT_FOUND: $pcl"
}
$pclExe = Get-ChildItem -LiteralPath $pcl -File -Filter '*.exe' |
        Where-Object Name -Match 'Plain Craft Launcher' | Select-Object -First 1
if ($null -eq $pclExe) { throw "PCL2_EXECUTABLE_NOT_FOUND: $pcl" }
if (-not (Test-Path -LiteralPath $source -PathType Container)) {
    throw "PCL2_SOURCE_INSTANCE_NOT_FOUND: $source"
}
if (-not (Test-Path -LiteralPath $releaseJar -PathType Leaf)) {
    throw "PCL2_RELEASE_JAR_NOT_FOUND: $releaseJar"
}
if (-not (Test-Path -LiteralPath $helper -PathType Leaf)) {
    throw "PCL2_BACKUP_HELPER_NOT_FOUND: $helper"
}
Assert-SafeTarget $target (Join-Path $minecraft 'versions')
if ($target.Equals($source, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'PCL2_ACCEPTANCE_SOURCE_TARGET_COLLISION'
}
if (Test-Path -LiteralPath $target) {
    throw "PCL2_ACCEPTANCE_TARGET_ALREADY_EXISTS: $target"
}
if ($testData.StartsWith($repository.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "PCL2_ACCEPTANCE_TEST_DATA_IN_REPOSITORY: $testData"
}
if ($testData.StartsWith($source.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -or
        $testData.StartsWith($target.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "PCL2_ACCEPTANCE_TEST_DATA_IN_INSTANCE: $testData"
}
$running = @(Get-CimInstance Win32_Process | Where-Object {
    $_.ExecutablePath -like ($pcl + '\*') -or
    ($_.Name -match '^javaw?\.exe$' -and $_.CommandLine -like ('*' + $minecraft + '*'))
})
if ($running.Count -gt 0) {
    throw "PCL2_ACCEPTANCE_GAME_OR_LAUNCHER_RUNNING: $($running.ProcessId -join ',')"
}

$sourceJson = Join-Path $source "$SourceInstanceName.json"
if (-not (Test-Path -LiteralPath $sourceJson -PathType Leaf)) {
    throw "PCL2_SOURCE_VERSION_JSON_NOT_FOUND: $sourceJson"
}
$sourceProfile = Get-Content -LiteralPath $sourceJson -Raw | ConvertFrom-Json
if ($sourceProfile.inheritsFrom -ne '1.20.1') {
    throw "PCL2_SOURCE_MINECRAFT_MISMATCH: $($sourceProfile.inheritsFrom)"
}
$forgeLoader = @($sourceProfile.libraries | Where-Object {
    $_.name -eq 'net.minecraftforge:fmlloader:1.20.1-47.4.0'
})
$forgeDisplay = @($sourceProfile.libraries | Where-Object {
    $_.name -eq 'net.minecraftforge:fmlearlydisplay:1.20.1-47.4.0'
})
if ($forgeLoader.Count -ne 1 -or $forgeDisplay.Count -ne 1) {
    throw 'PCL2_SOURCE_FORGE_47_4_0_REQUIRED'
}

New-Item -ItemType Directory -Force -Path $target, $backupRoot, $evidenceRoot, $toolsRoot | Out-Null
$manifest = [Collections.Generic.List[object]]::new()
$copiedBytes = [long]0
foreach ($scope in $copyScopes) {
    $sourceScope = Join-Path $source $scope
    if (-not (Test-Path -LiteralPath $sourceScope -PathType Container)) { continue }
    $reparse = @(Get-ChildItem -LiteralPath $sourceScope -Directory -Force -Recurse |
            Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 })
    if ($reparse.Count -gt 0) {
        throw "PCL2_SOURCE_REPARSE_POINT_REFUSED: $($reparse[0].FullName)"
    }
    foreach ($file in Get-ChildItem -LiteralPath $sourceScope -File -Force -Recurse | Sort-Object FullName) {
        $relative = [IO.Path]::GetRelativePath($sourceScope, $file.FullName)
        $destination = Join-Path (Join-Path $target $scope) $relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        $sourceHash = Get-Sha256 $file.FullName
        Copy-Item -LiteralPath $file.FullName -Destination $destination
        $destinationHash = Get-Sha256 $destination
        if ($sourceHash -ne $destinationHash) {
            throw "PCL2_ACCEPTANCE_COPY_HASH_MISMATCH: $($file.FullName)"
        }
        $copiedBytes += $file.Length
        $manifest.Add([pscustomobject][ordered]@{
            relativePath = "$scope/$($relative.Replace('\','/'))"
            bytes = $file.Length
            sha256 = $sourceHash
            sourceKind = 'existing-deceasedcraft-instance-read-only-pack-input'
        })
    }
}

$targetProfile = Get-Content -LiteralPath $sourceJson -Raw | ConvertFrom-Json
$targetProfile.id = $TargetInstanceName
$targetJson = Join-Path $target "$TargetInstanceName.json"
$targetProfile | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $targetJson -Encoding utf8

$pclConfig = Join-Path $target 'PCL'
New-Item -ItemType Directory -Force -Path $pclConfig | Out-Null
@(
    'VersionArgumentIndieV2:True'
    'Logo:pack://application:,,,/Plain Craft Launcher 2;component/Images/Blocks/Anvil.png'
) | Set-Content -LiteralPath (Join-Path $pclConfig 'Setup.ini') -Encoding utf8

$mods = Join-Path $target 'mods'
New-Item -ItemType Directory -Force -Path $mods | Out-Null
$existingAgents = @(Get-ChildItem -LiteralPath $mods -File | Where-Object {
    $_.Name -match '(?i)steve[-_ ].*(agent)|steve-create-agent|steve-industrial-agent'
})
if ($existingAgents.Count -gt 0) {
    throw "PCL2_ACCEPTANCE_PREEXISTING_AGENT_JAR: $($existingAgents.Name -join ',')"
}
$agentTarget = Join-Path $mods "steve-industrial-agent-$version.jar"
Copy-Item -LiteralPath $releaseJar -Destination $agentTarget
$agentSourceHash = Get-Sha256 $releaseJar
$agentTargetHash = Get-Sha256 $agentTarget
if ($agentSourceHash -ne $agentTargetHash) { throw 'PCL2_ACCEPTANCE_AGENT_HASH_MISMATCH' }

$config = Join-Path $target 'config'
New-Item -ItemType Directory -Force -Path $config | Out-Null
$targetPath = $target.Replace('\','/')
$backupPath = $backupRoot.Replace('\','/')
$importantPath = $source.Replace('\','/')
@(
    '# Generated for the PCL2 public-alpha visual acceptance instance.'
    '[runtime]'
    'configSchemaVersion = 1'
    'runtimeMode = "DISPOSABLE_TEST_WORLD"'
    "testInstanceRoot = `"$targetPath`""
    "allowedTestWorlds = [`"$worldName`"]"
    "backupRoot = `"$backupPath`""
    "importantInstanceRoots = [`"$importantPath`"]"
    'maxRegionSize = 32'
    'requireBackup = true'
    'requireWorldMarker = true'
    'allowDirectPilot = true'
    'diagnosticsRedaction = true'
    'formalWorldPolicy = "DENY_CONFIGURED_ROOTS"'
) | Set-Content -LiteralPath (Join-Path $config 'steve-industrial-agent-common.toml') -Encoding utf8

Copy-Item -LiteralPath $helper -Destination (Join-Path $toolsRoot 'Complete-IndustrialAgentBackup.ps1')
@'
/industrialagent version
/industrialagent compatibility
/industrialagent setup show-config
/industrialagent setup status
/industrialagent setup mark-test-world
/industrialagent setup validate
/industrialagent backup prepare
/industrialagent setup validate
/industrialagent backup verify
/industrialagent pilot region here 32 32 20
/industrialagent pilot region preview
/industrialagent pilot region confirm
/industrialagent pilot readiness minecraft:gravel 3
/industrialagent pilot dry-run minecraft:gravel 3
/industrialagent pilot start minecraft:gravel 3
/industrialagent pilot status
/industrialagent pilot cleanup preview
/industrialagent pilot cleanup
/industrialagent pilot readiness create:iron_sheet 2
/industrialagent pilot dry-run create:iron_sheet 2
/industrialagent pilot start create:iron_sheet 2
/industrialagent pilot status
/industrialagent pilot cancel
/industrialagent pilot hold build
/industrialagent pilot start create:iron_sheet 2
/industrialagent pilot recovery-status
/industrialagent pilot resume
/industrialagent pilot status
/industrialagent pilot cleanup preview
/industrialagent pilot cleanup
'@ | Set-Content -LiteralPath (Join-Path $testData 'VISUAL-COMMANDS.txt') -Encoding utf8

$manifest | ForEach-Object { $_ | ConvertTo-Json -Compress } |
        Set-Content -LiteralPath (Join-Path $evidenceRoot 'copied-pack-manifest.jsonl') -Encoding utf8
$summary = [ordered]@{
    schema = 'steve-industrial:pcl2-public-alpha-acceptance/v1'
    status = 'PREPARED_NOT_LAUNCHED'
    pcl2Executable = $pclExe.FullName
    sourceInstance = $SourceInstanceName
    targetInstance = $TargetInstanceName
    targetGameDirectory = $target
    expectedWorldName = $worldName
    minecraft = '1.20.1'
    forge = '47.4.0'
    deceasedCraft = 'Beta 5.10.16'
    agentJar = $agentTarget
    agentSha256 = $agentTargetHash
    copiedPackFiles = $manifest.Count
    copiedPackBytes = $copiedBytes
    sourceSavesRead = $false
    accountOrTokenDataRead = $false
    logsCopied = $false
    privateOptionsCopied = $false
    existingSourceInstanceModified = $false
    visualAcceptance = 'PENDING_USER'
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidenceRoot 'acceptance-summary.json') -Encoding utf8

foreach ($name in $forbiddenRootNames) {
    if (Test-Path -LiteralPath (Join-Path $target $name)) {
        throw "PCL2_ACCEPTANCE_PRIVATE_ROOT_COPIED: $name"
    }
}
$agentCount = @(Get-ChildItem -LiteralPath $mods -File | Where-Object {
    $_.Name -match '(?i)steve[-_ ].*(agent)|steve-create-agent|steve-industrial-agent'
}).Count
if ($agentCount -ne 1) { throw "PCL2_ACCEPTANCE_AGENT_COUNT_INVALID: $agentCount" }

Write-Output "PCL2_ACCEPTANCE_INSTANCE_PREPARED target=$target world=`"$worldName`" agentSha256=$agentTargetHash packFiles=$($manifest.Count) sourceSavesRead=false accountOrTokenDataRead=false existingSourceInstanceModified=false visualAcceptance=PENDING_USER"
Write-Output "PCL2 executable: $($pclExe.FullName)"
Write-Output "Commands: $(Join-Path $testData 'VISUAL-COMMANDS.txt')"
Write-Output "Backup helper: $(Join-Path $toolsRoot 'Complete-IndustrialAgentBackup.ps1')"
