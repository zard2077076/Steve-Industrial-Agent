param(
    [string]$ClientVersionRoot,
    [string]$SanitizedPackRoot,
    [string]$InstanceRoot,
    [switch]$Refresh
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$local = @{}
$localPath = Join-Path $repoRoot 'local.properties'
if (Test-Path -LiteralPath $localPath) {
    foreach ($line in Get-Content -LiteralPath $localPath) {
        if ($line -match '^\s*([^#][^=]*)=(.*)$') { $local[$Matches[1].Trim()] = $Matches[2].Trim() }
    }
}
$pclRoot = if ($local.ContainsKey('pcl2_root')) { [IO.Path]::GetFullPath($local['pcl2_root']) } `
        elseif ($env:PCL2_ROOT) { [IO.Path]::GetFullPath($env:PCL2_ROOT) } else { '' }
if ([string]::IsNullOrWhiteSpace($ClientVersionRoot)) {
    if ([string]::IsNullOrWhiteSpace($pclRoot)) { throw 'Configure pcl2_root in ignored local.properties.' }
    $ClientVersionRoot = Join-Path $pclRoot '.minecraft\versions\DeceasedCraft_Beta 5.10.16'
}
$workRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'work'))
$allowedRoot = [IO.Path]::GetFullPath((Join-Path $workRoot 'isolated-player'))
if ([string]::IsNullOrWhiteSpace($SanitizedPackRoot)) {
    $SanitizedPackRoot = Join-Path $workRoot 'isolated-pack\deceasedcraft-r09\run'
}
if ([string]::IsNullOrWhiteSpace($InstanceRoot)) {
    $InstanceRoot = Join-Path $allowedRoot 'SteveAgent_DeceasedCraft_Test'
}
$instance = [IO.Path]::GetFullPath($InstanceRoot)
$instanceName = Split-Path -Leaf $instance
$run = Join-Path $instance 'run'
$evidence = Join-Path $instance 'evidence'
$logRoot = Join-Path $workRoot 'logs'
$log = Join-Path $logRoot ('iwp-instance-builder-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
$productionJarCandidates = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'forge-create-1.20.1\build\libs') `
        -File -Filter 'steve-industrial-agent-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' })
if ($productionJarCandidates.Count -ne 1) {
    throw "Expected exactly one clean Steve Industrial Agent production JAR, found $($productionJarCandidates.Count)."
}
$productionJar = $productionJarCandidates[0].FullName
$instanceIdentity = 'test-instance:steveagent-deceasedcraft-test-v1'
$formalWorldIdentity = 'world:ded38fd9f680d10751612d7b0d95e9a14c228918a8e8e4b5daf308a20762dcb3'

function Fail-Iwp([string]$Code, [string]$Stage, [string]$Detail, [string]$Next) {
    if (-not (Test-Path -LiteralPath $logRoot)) {
        New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
    }
    $line = "IWP_INSTANCE_FAILURE code=$Code stage=$Stage instance=$instance gameDir=$run detail=`"$Detail`" next=`"$Next`""
    $line | Set-Content -LiteralPath $log -Encoding utf8
    throw $line
}

function Assert-SafeChild([string]$Path, [string]$Parent) {
    $pathValue = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $parentValue = [IO.Path]::GetFullPath($Parent).TrimEnd('\')
    if (-not $pathValue.StartsWith($parentValue + '\', [StringComparison]::OrdinalIgnoreCase)) {
        Fail-Iwp 'FORMAL_WORLD_FORBIDDEN' 'path-policy' `
                "Instance is outside the repository work/isolated-player root: $pathValue" `
                'Use the default repository-owned instance path.'
    }
}

function Relative([string]$Base, [string]$Path) {
    return [IO.Path]::GetRelativePath($Base, $Path).Replace('\', '/')
}

Assert-SafeChild $instance $allowedRoot
if (-not [string]::IsNullOrWhiteSpace($pclRoot) -and
        $instance.StartsWith($pclRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    Fail-Iwp 'FORMAL_WORLD_FORBIDDEN' 'path-policy' 'Instance resolves inside the configured formal platform root.' `
            'Use the repository-owned instance path.'
}
$ignored = & git -C $repoRoot check-ignore $instance 2>$null
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($ignored -join ''))) {
    Fail-Iwp 'FORMAL_WORLD_FORBIDDEN' 'git-ignore-policy' 'Instance path is not ignored by Git.' `
            'Keep all player worlds and pack files below the ignored work directory.'
}
if (-not (Test-Path -LiteralPath $ClientVersionRoot -PathType Container)) {
    Fail-Iwp 'TEST_INSTANCE_NOT_FOUND' 'source-preflight' 'Formal client version source is absent.' `
            'Restore the existing DeceasedCraft client version without creating a substitute in PCL2.'
}
if (-not (Test-Path -LiteralPath $SanitizedPackRoot -PathType Container)) {
    Fail-Iwp 'TEST_INSTANCE_NOT_FOUND' 'source-preflight' 'Sanitized R-09 pack mirror is absent.' `
            'Rebuild the repository-owned R-09 profile first.'
}
if (-not (Test-Path -LiteralPath $productionJar -PathType Leaf)) {
    Fail-Iwp 'TEST_INSTANCE_NOT_FOUND' 'jar-preflight' 'Clean production JAR is absent.' `
            'Run scripts/Build.ps1, then retry instance creation.'
}
if (Test-Path -LiteralPath $instance) {
    if (-not $Refresh) {
        Fail-Iwp 'PILOT_DUPLICATE_SESSION' 'instance-create' 'Instance already exists and refresh was not requested.' `
                'Use the retained instance or explicitly refresh only this ignored instance.'
    }
    $resolvedInstance = (Resolve-Path -LiteralPath $instance).Path
    Assert-SafeChild $resolvedInstance $allowedRoot
    Remove-Item -LiteralPath $resolvedInstance -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $run, $evidence, $logRoot | Out-Null
$manifest = [Collections.Generic.List[object]]::new()

function Copy-VerifiedTree([string]$SourceRoot, [string]$DestinationRoot, [string]$SourceKind) {
    if (-not (Test-Path -LiteralPath $SourceRoot -PathType Container)) { return }
    New-Item -ItemType Directory -Force -Path $DestinationRoot | Out-Null
    foreach ($file in Get-ChildItem -LiteralPath $SourceRoot -Recurse -File | Sort-Object FullName) {
        $relative = [IO.Path]::GetRelativePath($SourceRoot, $file.FullName)
        $destination = Join-Path $DestinationRoot $relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        $sourceHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        Copy-Item -LiteralPath $file.FullName -Destination $destination
        $destinationHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($sourceHash -ne $destinationHash) {
            Fail-Iwp 'TEST_WORLD_BACKUP_INVALID' 'copy-verify' "Copy hash mismatch: $($file.FullName)" `
                    'Retain the failed evidence and check disk health before retrying.'
        }
        $manifest.Add([pscustomobject][ordered]@{
            sourceKind = $SourceKind
            relativePath = Relative $run $destination
            sourcePath = $file.FullName
            bytes = $file.Length
            sourceLastWriteTimeUtc = $file.LastWriteTimeUtc.ToString('o')
            sha256 = $sourceHash
        })
    }
}

# Client mod JARs are copied opaquely. No formal save, account cache, server list,
# options, logs, screenshots, chat or inventory file is enumerated or copied.
Copy-VerifiedTree (Join-Path $ClientVersionRoot 'mods') (Join-Path $run 'mods') 'formal-client-mods-read-only-source'

# Configuration/data comes from the already sanitized repository-owned R-09 mirror.
foreach ($scope in @('config', 'defaultconfigs', 'kubejs', 'generated_datapacks',
        'customnpcs', 'patchouli_books', 'tacz')) {
    Copy-VerifiedTree (Join-Path $SanitizedPackRoot $scope) (Join-Path $run $scope) 'sanitized-r09-mirror'
}

# These client presentation assets contain no player save/session/account material.
foreach ($scope in @('fancymenu_data', 'resourcepacks')) {
    Copy-VerifiedTree (Join-Path $ClientVersionRoot $scope) (Join-Path $run $scope) 'formal-client-presentation-source'
}

# Remove any prior copy of this mod from the isolated destination, then deploy exactly the clean build.
$agentJars = @(Get-ChildItem -LiteralPath (Join-Path $run 'mods') -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '(?i)steve[-_ ].*(agent)|steve-create-agent|steve-industrial-agent' })
foreach ($jar in $agentJars) { Remove-Item -LiteralPath $jar.FullName -Force }
$deployedJar = Join-Path $run 'mods\steve-create-agent-forge-1.20.1-0.1.0-SNAPSHOT.jar'
Copy-Item -LiteralPath $productionJar -Destination $deployedJar
$jarHash = (Get-FileHash -LiteralPath $deployedJar -Algorithm SHA256).Hash.ToLowerInvariant()
$buildHash = (Get-FileHash -LiteralPath $productionJar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($jarHash -ne $buildHash) {
    Fail-Iwp 'TEST_WORLD_BACKUP_INVALID' 'jar-deploy' 'Deployed production JAR hash mismatch.' `
            'Rebuild and deploy only to the isolated instance.'
}

New-Item -ItemType Directory -Force -Path (Join-Path $run 'saves'), (Join-Path $run 'logs'), `
        (Join-Path $run 'crash-reports'), (Join-Path $run 'screenshots') | Out-Null
'steve-industrial:iwp-test-instance/v1' |
        Set-Content -LiteralPath (Join-Path $run '.steve-industrial-writable-test-instance') -Encoding ascii
'steve-industrial:deployment-dry-run/v1' |
        Set-Content -LiteralPath (Join-Path $run '.steve-industrial-deployment-dry-run') -Encoding ascii
'steve-industrial:isolated-execution/v1' |
        Set-Content -LiteralPath (Join-Path $run '.steve-industrial-execution-test') -Encoding ascii

$manifestPath = Join-Path $evidence 'instance-manifest.jsonl'
$manifest | ForEach-Object { $_ | ConvertTo-Json -Compress } |
        Set-Content -LiteralPath $manifestPath -Encoding utf8

# Re-read only the source files already copied and prove the formal sources did not change.
foreach ($entry in $manifest | Where-Object sourceKind -Like 'formal-client-*') {
    $source = Get-Item -LiteralPath $entry.sourcePath
    $currentHash = (Get-FileHash -LiteralPath $source.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($source.Length -ne $entry.bytes -or $currentHash -ne $entry.sha256) {
        Fail-Iwp 'FORMAL_WORLD_FORBIDDEN' 'external-postcheck' `
                "Formal read-only source changed during copy: $($source.FullName)" `
                'Stop; retain evidence; do not repair or overwrite the formal instance.'
    }
}

$head = (& git -C $repoRoot rev-parse HEAD).Trim()
$summary = [ordered]@{
    schema = 'steve-industrial:iwp-test-instance/v1'
    instanceName = $instanceName
    instanceIdentity = $instanceIdentity
    instanceRoot = $instance
    gameDirectory = $run
    expectedWorldName = 'Steve Agent Test'
    formalWorldIdentity = $formalWorldIdentity
    productionJar = $deployedJar
    productionJarSha256 = $jarHash
    gitHead = $head
    copiedFiles = $manifest.Count
    copiedBytes = [long](($manifest | Measure-Object bytes -Sum).Sum)
    savesIndependent = $true
    configIndependent = $true
    logsIndependent = $true
    formalSavesRead = $false
    accountOrTokenFilesRead = $false
    formalMutation = $false
    worldMarkerPending = $true
}
$summaryPath = Join-Path $evidence 'instance-summary.json'
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $summaryPath -Encoding utf8
$line = "IWP_INSTANCE_BUILD_PASS instance=$instance gameDir=$run files=$($manifest.Count) bytes=$($summary.copiedBytes) jarSha256=$jarHash savesIndependent=true configIndependent=true logsIndependent=true formalSavesRead=false accountOrTokenFilesRead=false formalMutation=false worldMarkerPending=true"
$line | Set-Content -LiteralPath $log -Encoding utf8
Write-Output $line
Write-Output "Instance summary: $summaryPath"
Write-Output "Builder log: $log"
