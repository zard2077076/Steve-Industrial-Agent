param(
    [string]$InstanceRoot,
    [string]$MinecraftRoot,
    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$local = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $repoRoot 'local.properties')) {
    if ($line -match '^\s*([^#][^=]*)=(.*)$') { $local[$Matches[1].Trim()] = $Matches[2].Trim() }
}
if (-not $local.ContainsKey('pcl2_root') -and -not $env:PCL2_ROOT) {
    throw 'Configure pcl2_root in ignored local.properties.'
}
$pclRoot = if ($local.ContainsKey('pcl2_root')) { [IO.Path]::GetFullPath($local['pcl2_root']) } `
        else { [IO.Path]::GetFullPath($env:PCL2_ROOT) }
if ([string]::IsNullOrWhiteSpace($MinecraftRoot)) { $MinecraftRoot = Join-Path $pclRoot '.minecraft' }
if ([string]::IsNullOrWhiteSpace($InstanceRoot)) {
    $InstanceRoot = Join-Path $repoRoot 'work\isolated-player\SteveAgent_DeceasedCraft_Test'
}
$instance = (Resolve-Path -LiteralPath $InstanceRoot).Path
$instanceName = Split-Path -Leaf $instance
$run = (Resolve-Path -LiteralPath (Join-Path $instance 'run')).Path
$expectedRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'work\isolated-player')).TrimEnd('\') + '\'
if (-not $instance.StartsWith($expectedRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $run.StartsWith($pclRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "IWP launch refused: instance path is outside repository work/isolated-player: $run"
}
$instanceMarker = Join-Path $run '.steve-industrial-writable-test-instance'
if (-not (Test-Path -LiteralPath $instanceMarker -PathType Leaf) -or
        (Get-Content -LiteralPath $instanceMarker -Raw).Trim() -ne 'steve-industrial:iwp-test-instance/v1') {
    throw 'IWP launch refused: exact isolated instance marker is absent or invalid.'
}
$agentJar = Join-Path $run 'mods\steve-create-agent-forge-1.20.1-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $agentJar -PathType Leaf)) {
    throw 'IWP launch refused: production Steve Industrial Agent JAR is absent.'
}
$running = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -like "*$run*"
})
if ($running.Count -gt 0) {
    throw "IWP launch refused: this exact test instance is already running (PID $($running.ProcessId -join ','))."
}

if (-not $local.ContainsKey('java_home')) { throw 'java_home is missing from ignored local.properties.' }
$java = Join-Path $local['java_home'] 'bin\javaw.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw "Java 17 javaw.exe is absent: $java" }

$baseId = '1.20.1'
$childId = 'DeceasedCraft_Beta 5.10.16'
$baseJsonPath = Join-Path $MinecraftRoot "versions\$baseId\$baseId.json"
$childJsonPath = Join-Path $MinecraftRoot "versions\$childId\$childId.json"
$baseJar = Join-Path $MinecraftRoot "versions\$baseId\$baseId.jar"
$natives = Join-Path $MinecraftRoot "versions\$childId\$childId-natives"
foreach ($required in @($baseJsonPath, $childJsonPath, $baseJar, $natives,
        (Join-Path $MinecraftRoot 'libraries'), (Join-Path $MinecraftRoot 'assets'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Read-only Minecraft launch dependency is absent: $required" }
}
$base = Get-Content -LiteralPath $baseJsonPath -Raw -Encoding utf8 | ConvertFrom-Json
$child = Get-Content -LiteralPath $childJsonPath -Raw -Encoding utf8 | ConvertFrom-Json

function Rule-Matches($rule) {
    if ($null -ne $rule.os) {
        if ($null -ne $rule.os.name -and $rule.os.name -ne 'windows') { return $false }
        if ($null -ne $rule.os.arch -and $rule.os.arch -ne $env:PROCESSOR_ARCHITECTURE.ToLowerInvariant()) { return $false }
    }
    if ($null -ne $rule.features) {
        foreach ($property in $rule.features.PSObject.Properties) {
            if ([bool]$property.Value) { return $false }
        }
    }
    return $true
}

function Rules-Allow($rules) {
    if ($null -eq $rules) { return $true }
    $allowed = $false
    foreach ($rule in @($rules)) {
        if (Rule-Matches $rule) { $allowed = $rule.action -eq 'allow' }
    }
    return $allowed
}

$libraryPaths = [Collections.Generic.List[string]]::new()
foreach ($library in @($base.libraries) + @($child.libraries)) {
    if (-not (Rules-Allow $library.rules)) { continue }
    if ($null -ne $library.downloads -and $null -ne $library.downloads.artifact -and
            $null -ne $library.downloads.artifact.path) {
        $path = Join-Path (Join-Path $MinecraftRoot 'libraries') ($library.downloads.artifact.path -replace '/', '\')
        if (Test-Path -LiteralPath $path -PathType Leaf) { $libraryPaths.Add($path) }
    }
}
$libraryPaths.Add($baseJar)
$classpath = (@($libraryPaths | Select-Object -Unique) -join [IO.Path]::PathSeparator)

$offlineName = 'SteveAgentTest'
$offlineUuid = '6a17f4d39c0137a1b62a2da91c7d9af7'
$backupPointerPath = Join-Path $instance 'evidence\accepted-world-backup.properties'
if (-not (Test-Path -LiteralPath $backupPointerPath -PathType Leaf)) {
    throw 'IWP launch refused: accepted IWP-05 backup pointer is missing.'
}
$backupPointer = @{}
foreach ($line in Get-Content -LiteralPath $backupPointerPath) {
    if ($line -match '^([^=]+)=(.*)$') { $backupPointer[$Matches[1].Trim()] = $Matches[2].Trim() }
}
foreach ($required in @('schema','evidenceRoot','backupRoot','backupTarget','backupIdentity','manifestHash','worldIdentity')) {
    if (-not $backupPointer.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($backupPointer[$required])) {
        throw "IWP launch refused: accepted backup pointer lacks $required."
    }
}
if ($backupPointer['schema'] -ne 'steve-industrial:iwp-accepted-backup/v1' -or
        $backupPointer['manifestHash'] -notmatch '^[0-9a-f]{64}$') {
    throw 'IWP launch refused: accepted backup pointer schema or manifest is invalid.'
}
$resolvedBackupRoot = (Resolve-Path -LiteralPath $backupPointer['backupRoot']).Path
$resolvedBackupTarget = (Resolve-Path -LiteralPath $backupPointer['backupTarget']).Path
$resolvedBackupEvidence = (Resolve-Path -LiteralPath $backupPointer['evidenceRoot']).Path
$allowedBackupRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'work\writable-world-backups')).TrimEnd('\')
if (-not $resolvedBackupRoot.Equals($allowedBackupRoot, [StringComparison]::OrdinalIgnoreCase) -or
        -not $resolvedBackupTarget.StartsWith($resolvedBackupRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
        -not $resolvedBackupEvidence.StartsWith($resolvedBackupRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
        $resolvedBackupTarget.StartsWith($pclRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'IWP launch refused: accepted backup paths escaped the repository-owned backup root.'
}
$variables = @{
    '${auth_player_name}' = $offlineName
    '${version_name}' = $childId
    '${game_directory}' = $run
    '${assets_root}' = (Join-Path $MinecraftRoot 'assets')
    '${assets_index_name}' = $base.assetIndex.id
    '${auth_uuid}' = $offlineUuid
    '${auth_access_token}' = '0'
    '${clientid}' = '0'
    '${auth_xuid}' = '0'
    '${user_type}' = 'legacy'
    '${version_type}' = 'release'
    '${natives_directory}' = $natives
    '${launcher_name}' = 'SteveIndustrialAgentIWP'
    '${launcher_version}' = '1'
    '${classpath}' = $classpath
    '${classpath_separator}' = [IO.Path]::PathSeparator
    '${library_directory}' = (Join-Path $MinecraftRoot 'libraries')
}

function Expand-Value([string]$value) {
    foreach ($entry in $variables.GetEnumerator()) { $value = $value.Replace($entry.Key, [string]$entry.Value) }
    return $value
}

# Windows PowerShell 5.1 runs on .NET Framework, where ProcessStartInfo has no
# ArgumentList collection. Build one Windows command line with the standard
# quote/backslash rules so paths containing spaces remain single arguments.
function Quote-WindowsArgument([string]$value) {
    if ($null -eq $value) { return '""' }
    if ($value.Length -gt 0 -and $value -notmatch '[\s"]') { return $value }
    $builder = New-Object Text.StringBuilder
    [void]$builder.Append([char]34)
    $backslashes = 0
    foreach ($character in $value.ToCharArray()) {
        if ($character -eq [char]92) {
            $backslashes++
            continue
        }
        if ($character -eq [char]34) {
            if ($backslashes -gt 0) {
                [void]$builder.Append([char]92, ($backslashes * 2))
            }
            [void]$builder.Append([char]92)
            [void]$builder.Append([char]34)
        } else {
            if ($backslashes -gt 0) {
                [void]$builder.Append([char]92, $backslashes)
            }
            [void]$builder.Append($character)
        }
        $backslashes = 0
    }
    if ($backslashes -gt 0) {
        [void]$builder.Append([char]92, ($backslashes * 2))
    }
    [void]$builder.Append([char]34)
    return $builder.ToString()
}

function Add-VersionArguments([Collections.Generic.List[string]]$target, $arguments) {
    foreach ($argument in @($arguments)) {
        if ($argument -is [string]) { $target.Add((Expand-Value $argument)); continue }
        if (-not (Rules-Allow $argument.rules)) { continue }
        foreach ($value in @($argument.value)) { $target.Add((Expand-Value ([string]$value))) }
    }
}

$jvm = [Collections.Generic.List[string]]::new()
$jvm.Add('-Xms1G')
$jvm.Add('-Xmx6G')
$jvm.Add('-Dfile.encoding=UTF-8')
$jvm.Add('-Dsteve_industrial.test.goalDrivenExecutionGameTest=true')
$jvm.Add("-Dsteve_industrial.iwp.expectedGameDir=$run")
$jvm.Add("-Dsteve_industrial.iwp.forbiddenRoot=$pclRoot")
$jvm.Add('-Dsteve_industrial.iwp.testInstanceIdentity=test-instance:steveagent-deceasedcraft-test-v1')
$jvm.Add('-Dsteve_industrial.iwp.formalWorldIdentity=world:ded38fd9f680d10751612d7b0d95e9a14c228918a8e8e4b5daf308a20762dcb3')
$jvm.Add("-Dsteve_industrial.deployment.expectedGameDir=$run")
$jvm.Add("-Dsteve_industrial.deployment.forbiddenRoot=$pclRoot")
$jvm.Add("-Dsteve_industrial.execution.expectedGameDir=$run")
$jvm.Add("-Dsteve_industrial.execution.forbiddenRoot=$pclRoot")
$jvm.Add("-Dsteve_industrial.iwp.backupEvidenceRoot=$resolvedBackupEvidence")
$jvm.Add("-Dsteve_industrial.iwp.backupRoot=$resolvedBackupRoot")
$jvm.Add("-Dsteve_industrial.iwp.backupTarget=$resolvedBackupTarget")
$jvm.Add("-Dsteve_industrial.iwp.backupIdentity=$($backupPointer['backupIdentity'])")
$jvm.Add("-Dsteve_industrial.iwp.backupManifest=$($backupPointer['manifestHash'])")
$jvm.Add("-Dsteve_industrial.iwp.backupWorldIdentity=$($backupPointer['worldIdentity'])")
Add-VersionArguments $jvm $base.arguments.jvm
# Forge's child version JSON uses ${version_name}.jar in -DignoreList. This
# standalone profile inherits the base client JAR instead of having a child
# version JAR, so SecureJarHandler must ignore the base JAR name.
$variables['${version_name}'] = $baseId
Add-VersionArguments $jvm $child.arguments.jvm
$variables['${version_name}'] = $childId

$expectedIgnoredClientJar = "$baseId.jar"
$baseClientJarIgnored = $false
foreach ($jvmArgument in $jvm) {
    if ($jvmArgument -notlike '-DignoreList=*') { continue }
    $ignoredNames = @($jvmArgument.Substring('-DignoreList='.Length) -split ',')
    if ($ignoredNames -contains $expectedIgnoredClientJar) {
        $baseClientJarIgnored = $true
        break
    }
}
if (-not $baseClientJarIgnored) {
    throw "IWP launch refused: Forge ignoreList does not contain inherited client JAR $expectedIgnoredClientJar."
}

$game = [Collections.Generic.List[string]]::new()
Add-VersionArguments $game $base.arguments.game
Add-VersionArguments $game $child.arguments.game
$game.Add('--width'); $game.Add('1280'); $game.Add('--height'); $game.Add('720')

$arguments = @($jvm) + @($child.mainClass) + @($game)
$commandLine = (@($arguments | ForEach-Object { Quote-WindowsArgument ([string]$_) }) -join ' ')
if ([string]::IsNullOrWhiteSpace($commandLine)) { throw 'IWP launch refused: generated command line is empty.' }
if ($PlanOnly) {
    Write-Output "IWP_CLIENT_LAUNCH_PLAN_PASS gameDir=$run java=$java libraries=$(@($libraryPaths | Select-Object -Unique).Count) arguments=$($arguments.Count) commandLineBuilt=true powershellCompatible=5.1+ inheritedClientJarIgnored=$expectedIgnoredClientJar threeModeBoundaryEnabled=true backupIdentity=$($backupPointer['backupIdentity']) backupManifest=$($backupPointer['manifestHash']) offlineIdentity=true accountOrTokenRead=false formalRootReadOnly=true"
    return
}
$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = $java
$start.WorkingDirectory = $run
$start.UseShellExecute = $false
$start.Arguments = $commandLine
$process = [Diagnostics.Process]::Start($start)
$pidPath = Join-Path $instance 'evidence\last-client-pid.txt'
"pid=$($process.Id)`nstartedAt=$((Get-Date).ToUniversalTime().ToString('o'))`ngameDir=$run" |
        Set-Content -LiteralPath $pidPath -Encoding utf8
Write-Output "IWP_CLIENT_STARTED pid=$($process.Id) gameDir=$run instance=$instanceName formalRootReadOnly=true"
