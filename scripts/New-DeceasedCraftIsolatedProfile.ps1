param(
    [string]$ClientRoot = 'D:\PCL2\.minecraft\versions\DeceasedCraft_Beta 5.10.16',
    [string]$ServerRoot = 'D:\PCL2\servers\DeceasedCraft_Server_Beta_5.10.16',
    [string]$ProfileRoot,
    [switch]$Refresh,
    [switch]$PlanOnly,
    [switch]$VerifyExternalSnapshot
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$workRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'work'))
$profilesRoot = [IO.Path]::GetFullPath((Join-Path $workRoot 'isolated-pack'))
if ([string]::IsNullOrWhiteSpace($ProfileRoot)) {
    $ProfileRoot = Join-Path $profilesRoot 'deceasedcraft-r09'
}
$profile = [IO.Path]::GetFullPath($ProfileRoot)
$sourceCopy = Join-Path $profile 'source'
$runDirectory = Join-Path $profile 'run'
$evidenceDirectory = Join-Path $profile 'evidence'
$logDirectory = Join-Path $repoRoot 'work\logs'
$started = (Get-Date).ToUniversalTime()
$failureLog = Join-Path $logDirectory ('deceasedcraft-profile-builder-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')

$copyScopes = @(
    [pscustomobject]@{ Name = 'mods'; AffectsRecipesOrRegistry = $true; Required = $true }
    [pscustomobject]@{ Name = 'config'; AffectsRecipesOrRegistry = $true; Required = $true }
    [pscustomobject]@{ Name = 'defaultconfigs'; AffectsRecipesOrRegistry = $true; Required = $false }
    [pscustomobject]@{ Name = 'kubejs'; AffectsRecipesOrRegistry = $true; Required = $true }
    [pscustomobject]@{ Name = 'generated_datapacks'; AffectsRecipesOrRegistry = $true; Required = $false }
    [pscustomobject]@{ Name = 'libraries'; AffectsRecipesOrRegistry = $false; Required = $true }
    [pscustomobject]@{ Name = 'customnpcs'; AffectsRecipesOrRegistry = $true; Required = $false }
    [pscustomobject]@{ Name = 'patchouli_books'; AffectsRecipesOrRegistry = $true; Required = $false }
    [pscustomobject]@{ Name = 'tacz'; AffectsRecipesOrRegistry = $true; Required = $false }
)
$snapshotScopes = @('mods', 'config', 'defaultconfigs', 'kubejs', 'generated_datapacks')
$forbiddenNames = @(
    'saves', 'world', 'playerdata', 'advancements', 'stats', 'logs', 'crash-reports',
    'backups', 'screenshots', 'resourcepacks', 'options.txt', 'servers.dat', 'servers.dat_old',
    'usercache.json', 'usernamecache.json', 'ops.json', 'whitelist.json', 'banned-ips.json',
    'banned-players.json'
)

function Write-R09Failure(
        [string]$Code,
        [string]$Stage,
        [string]$Detail,
        [string]$RelatedMod = 'none',
        [string]$Fingerprint = 'unavailable',
        [bool]$UserIntervention = $false,
        [string]$NextStep = 'Inspect the retained profile-builder log and correct only the isolated profile.') {
    $line = "R09_FAILURE code=$Code stage=$Stage gameDir=$runDirectory mod=$RelatedMod log=$failureLog fingerprint=$Fingerprint userIntervention=$($UserIntervention.ToString().ToLowerInvariant()) next=`"$NextStep`" detail=`"$Detail`""
    if (-not (Test-Path -LiteralPath $logDirectory)) {
        New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
    }
    $line | Add-Content -LiteralPath $failureLog -Encoding utf8
    throw $line
}

function Assert-SafeChildPath([string]$Path, [string]$Parent, [string]$Description) {
    $pathValue = [IO.Path]::GetFullPath($Path)
    $parentValue = [IO.Path]::GetFullPath($Parent)
    $prefix = $parentValue.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $pathValue.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -or
            $pathValue -eq $parentValue) {
        Write-R09Failure 'EXTERNAL_WRITE_RISK' 'profile-path-validation' `
                "$Description must be a strict child of $parentValue; actual=$pathValue" `
                -UserIntervention $true `
                -NextStep 'Choose a disposable profile beneath the repository work/isolated-pack directory.'
    }
}

function Resolve-ReadOnlyPclRoot([string]$Path, [string]$Description) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        Write-R09Failure 'PACK_PROFILE_SOURCE_NOT_FOUND' 'source-validation' `
                "$Description does not exist: $Path" -UserIntervention $true `
                -NextStep 'Restore or select the existing DeceasedCraft source directory; do not create it from this script.'
    }
    $resolved = (Resolve-Path -LiteralPath $Path).Path.TrimEnd('\')
    $pclRoot = (Resolve-Path -LiteralPath 'D:\PCL2').Path.TrimEnd('\')
    $prefix = $pclRoot + '\'
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        Write-R09Failure 'EXTERNAL_WRITE_RISK' 'source-validation' `
                "$Description is outside the approved read-only PCL2 source boundary: $resolved" `
                -UserIntervention $true -NextStep 'Use the existing DeceasedCraft directories under D:\PCL2 as read-only sources.'
    }
    return $resolved
}

function Get-RelativePath([string]$Base, [string]$Path) {
    return [IO.Path]::GetRelativePath($Base, $Path).Replace('\', '/')
}

function Get-DirectorySnapshot([string]$Root, [string]$Label) {
    $scopeRows = [Collections.Generic.List[object]]::new()
    $fingerprintLines = [Collections.Generic.List[string]]::new()
    foreach ($scope in $snapshotScopes) {
        $scopePath = Join-Path $Root $scope
        $files = if (Test-Path -LiteralPath $scopePath -PathType Container) {
            @(Get-ChildItem -LiteralPath $scopePath -File -Recurse -ErrorAction Stop | Sort-Object FullName)
        } else {
            @()
        }
        $scopeBytes = [long]0
        $scopeHashLines = [Collections.Generic.List[string]]::new()
        foreach ($file in $files) {
            $relative = Get-RelativePath $Root $file.FullName
            $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            $scopeBytes += $file.Length
            $line = "$relative|$($file.Length)|$($file.LastWriteTimeUtc.Ticks)|$hash"
            $scopeHashLines.Add($line)
            $fingerprintLines.Add($line)
        }
        $scopeDigest = Get-TextSha256 ($scopeHashLines -join "`n")
        $scopeRows.Add([ordered]@{
            scope = $scope
            fileCount = $files.Count
            bytes = $scopeBytes
            sha256 = $scopeDigest
        })
    }
    return [ordered]@{
        label = $Label
        root = $Root
        capturedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        fingerprint = Get-TextSha256 (($fingerprintLines | Sort-Object) -join "`n")
        scopes = @($scopeRows)
        savesOrWorldRead = $false
    }
}

function Get-TextSha256([string]$Text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
        return 'sha256:' + ([Convert]::ToHexString($sha.ComputeHash($bytes))).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-ModMetadata([IO.FileInfo]$Jar) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $modIds = [Collections.Generic.List[string]]::new()
    $versions = [Collections.Generic.List[string]]::new()
    $displayNames = [Collections.Generic.List[string]]::new()
    $modsToml = ''
    $manifestVersion = ''
    try {
        $archive = [IO.Compression.ZipFile]::OpenRead($Jar.FullName)
        try {
            $modsEntry = $archive.GetEntry('META-INF/mods.toml')
            if ($null -ne $modsEntry) {
                $reader = [IO.StreamReader]::new($modsEntry.Open())
                try { $modsToml = $reader.ReadToEnd() } finally { $reader.Dispose() }
                $blocks = [regex]::Matches($modsToml, '(?ms)^\[\[mods\]\]\s*(.*?)(?=^\[\[mods\]\]|^\[\[dependencies\.|\z)')
                foreach ($block in $blocks) {
                    $body = $block.Groups[1].Value
                    if ($body -match '(?m)^\s*modId\s*=\s*"([^"]+)"') { $modIds.Add($Matches[1]) }
                    if ($body -match '(?m)^\s*version\s*=\s*"([^"]+)"') { $versions.Add($Matches[1]) }
                    if ($body -match '(?m)^\s*displayName\s*=\s*"([^"]+)"') { $displayNames.Add($Matches[1]) }
                }
            }
            $manifestEntry = $archive.GetEntry('META-INF/MANIFEST.MF')
            if ($null -ne $manifestEntry) {
                $reader = [IO.StreamReader]::new($manifestEntry.Open())
                try {
                    $manifest = $reader.ReadToEnd()
                    if ($manifest -match '(?m)^Implementation-Version:\s*(.+?)\s*$') {
                        $manifestVersion = $Matches[1].Trim()
                    }
                } finally { $reader.Dispose() }
            }
        } finally { $archive.Dispose() }
    } catch {
        return [ordered]@{
            modIds = ''
            declaredVersions = ''
            manifestVersion = ''
            displayNames = ''
            metadataStatus = 'unreadable:' + $_.Exception.GetType().Name
            displayTest = ''
            createRelated = $false
            recipeOrKubeJsRelated = $false
        }
    }
    $displayTest = if ($modsToml -match '(?m)^\s*displayTest\s*=\s*"([^"]+)"') { $Matches[1] } else { '' }
    $identityText = (($modIds -join ',') + ' ' + $modsToml)
    return [ordered]@{
        modIds = $modIds -join ','
        declaredVersions = $versions -join ','
        manifestVersion = $manifestVersion
        displayNames = $displayNames -join ','
        metadataStatus = if ($modIds.Count -gt 0) { 'mods.toml' } else { 'no-mod-id' }
        displayTest = $displayTest
        createRelated = $identityText -match '(?i)(^|[^a-z])create([^a-z]|$)|flywheel|ponder'
        recipeOrKubeJsRelated = $identityText -match '(?i)kubejs|rhino|recipe|crafttweaker|datapack|create'
    }
}

Assert-SafeChildPath $profile $profilesRoot 'isolated profile'
$client = Resolve-ReadOnlyPclRoot $ClientRoot 'formal DeceasedCraft client source'
$server = Resolve-ReadOnlyPclRoot $ServerRoot 'DeceasedCraft dedicated-server source'
if ($profile.StartsWith('D:\PCL2\', [StringComparison]::OrdinalIgnoreCase) -or
        $runDirectory.StartsWith('D:\PCL2\', [StringComparison]::OrdinalIgnoreCase)) {
    Write-R09Failure 'WRONG_GAME_DIRECTORY' 'profile-path-validation' `
            "The isolated writable game directory resolves inside D:\PCL2: $runDirectory" `
            -UserIntervention $true -NextStep 'Use the default repository work/isolated-pack/deceasedcraft-r09 path.'
}
$ignoreProbe = & git -C $repoRoot check-ignore $profile 2>$null
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($ignoreProbe -join ''))) {
    Write-R09Failure 'EXTERNAL_WRITE_RISK' 'git-ignore-validation' `
            "Profile is not ignored by Git: $profile" -UserIntervention $true `
            -NextStep 'Add only the repository work directory to .gitignore; do not force-add pack files.'
}
foreach ($scope in $copyScopes | Where-Object Required) {
    if (-not (Test-Path -LiteralPath (Join-Path $server $scope.Name) -PathType Container)) {
        Write-R09Failure 'PACK_PROFILE_SOURCE_NOT_FOUND' 'source-validation' `
                "Required server source scope is missing: $($scope.Name)" `
                -NextStep 'Verify the existing dedicated-server package is complete without modifying it.'
    }
}
$forgeArgs = Join-Path $server 'libraries\net\minecraftforge\forge\1.20.1-47.4.0\win_args.txt'
if (-not (Test-Path -LiteralPath $forgeArgs -PathType Leaf)) {
    Write-R09Failure 'PACK_PROFILE_SOURCE_NOT_FOUND' 'source-validation' `
            "Forge 47.4.0 launch metadata is missing: $forgeArgs" `
            -NextStep 'Restore the server package launch libraries; do not substitute another Forge version.'
}

$plan = [ordered]@{
    profileRoot = $profile
    gameDir = $runDirectory
    sourceMirror = $sourceCopy
    evidenceDirectory = $evidenceDirectory
    clientReadOnlySource = $client
    serverReadOnlySource = $server
    copyScopes = @($copyScopes.Name)
    forbiddenNames = $forbiddenNames
    forgeArgs = 'libraries/net/minecraftforge/forge/1.20.1-47.4.0/win_args.txt'
    savesOrWorldRead = $false
    formalInstanceWritable = $false
}
Write-Output ("R09_PROFILE_PATH_VERIFIED gameDir={0} repository={1} ignored=true underPcl2=false" -f $runDirectory, $repoRoot)
if ($VerifyExternalSnapshot) {
    $baselinePath = Join-Path $evidenceDirectory 'external-after.json'
    if (-not (Test-Path -LiteralPath $baselinePath -PathType Leaf)) {
        Write-R09Failure 'PACK_PROFILE_SOURCE_NOT_FOUND' 'external-snapshot-verify' `
                "R-09 external baseline is missing: $baselinePath" `
                -NextStep 'Rebuild the ignored profile before launching any isolated server process.'
    }
    $baseline = Get-Content -Raw -LiteralPath $baselinePath | ConvertFrom-Json
    $current = [ordered]@{
        schema = 'steve-industrial:r09-external-snapshot/v1'
        client = Get-DirectorySnapshot $client 'formal-client'
        server = Get-DirectorySnapshot $server 'formal-dedicated-server'
    }
    if (-not (Test-Path -LiteralPath $evidenceDirectory)) {
        New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
    }
    $verificationPath = Join-Path $evidenceDirectory (
            'external-verification-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
    $current | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $verificationPath -Encoding utf8
    if ($baseline.client.fingerprint -ne $current.client.fingerprint -or
            $baseline.server.fingerprint -ne $current.server.fingerprint) {
        Write-R09Failure 'EXTERNAL_WRITE_RISK' 'external-snapshot-verify' `
                "Formal source fingerprint differs from the R-09A baseline: client=$($baseline.client.fingerprint)->$($current.client.fingerprint) server=$($baseline.server.fingerprint)->$($current.server.fingerprint)" `
                -Fingerprint $current.server.fingerprint -UserIntervention $true `
                -NextStep 'Stop all R-09 launches, retain evidence, identify changed formal files, and do not auto-repair D:\PCL2.'
    }
    Write-Output "R09_EXTERNAL_SNAPSHOT_PASS client=$($current.client.fingerprint) server=$($current.server.fingerprint) externalMutation=false savesOrWorldRead=false evidence=$verificationPath"
    return
}
if ($PlanOnly) {
    Write-Output ($plan | ConvertTo-Json -Depth 5)
    Write-Output 'R09_PROFILE_PLAN_PASS copied=false externalMutation=false savesOrWorldRead=false'
    return
}

if (Test-Path -LiteralPath $profile) {
    if (-not $Refresh) {
        Write-R09Failure 'PACK_PROFILE_COPY_FAILED' 'profile-refresh' `
                "Profile already exists and -Refresh was not specified: $profile" `
                -NextStep 'Review retained evidence, then rerun with -Refresh to recreate only this ignored profile.'
    }
    Remove-Item -LiteralPath $profile -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $sourceCopy, $runDirectory, $evidenceDirectory, $logDirectory | Out-Null

try {
    $before = [ordered]@{
        schema = 'steve-industrial:r09-external-snapshot/v1'
        client = Get-DirectorySnapshot $client 'formal-client'
        server = Get-DirectorySnapshot $server 'formal-dedicated-server'
    }
    $before | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'external-before.json') -Encoding utf8

    $manifestPath = Join-Path $evidenceDirectory 'source-manifest.jsonl'
    $copyExclusionsPath = Join-Path $evidenceDirectory 'copy-exclusions.jsonl'
    $inventory = [Collections.Generic.List[object]]::new()
    foreach ($scope in $copyScopes) {
        $sourceScope = Join-Path $server $scope.Name
        if (-not (Test-Path -LiteralPath $sourceScope -PathType Container)) { continue }
        $sourceTarget = Join-Path $sourceCopy $scope.Name
        $runTarget = Join-Path $runDirectory $scope.Name
        New-Item -ItemType Directory -Force -Path $sourceTarget, $runTarget | Out-Null
        foreach ($file in Get-ChildItem -LiteralPath $sourceScope -File -Recurse -ErrorAction Stop | Sort-Object FullName) {
            $relativeInScope = [IO.Path]::GetRelativePath($sourceScope, $file.FullName)
            $pathSegments = @($relativeInScope -split '[\\/]')
            $forbiddenSegment = @($pathSegments | Where-Object { $forbiddenNames -contains $_ } | Select-Object -First 1)
            if ($forbiddenSegment.Count -gt 0) {
                $excludedRecord = [ordered]@{
                    sourceRelativePath = "$($scope.Name)/$($relativeInScope.Replace('\', '/'))"
                    bytes = $file.Length
                    excludedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
                    reason = 'forbidden-private-or-generated-name'
                    matchedName = $forbiddenSegment[0]
                }
                ($excludedRecord | ConvertTo-Json -Compress) | Add-Content -LiteralPath $copyExclusionsPath -Encoding utf8
                continue
            }
            $sourceDestination = Join-Path $sourceTarget $relativeInScope
            $runDestination = Join-Path $runTarget $relativeInScope
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $sourceDestination), (Split-Path -Parent $runDestination) | Out-Null
            Copy-Item -LiteralPath $file.FullName -Destination $sourceDestination
            Copy-Item -LiteralPath $file.FullName -Destination $runDestination
            $sourceHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            $mirrorHash = (Get-FileHash -LiteralPath $sourceDestination -Algorithm SHA256).Hash.ToLowerInvariant()
            $runHash = (Get-FileHash -LiteralPath $runDestination -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($sourceHash -ne $mirrorHash -or $sourceHash -ne $runHash) {
                Write-R09Failure 'PACK_PROFILE_COPY_FAILED' 'copy-hash-validation' `
                        "SHA-256 mismatch while copying $($file.FullName)" `
                        -NextStep 'Keep the failed profile evidence and retry the copy after checking disk health.'
            }
            $record = [ordered]@{
                relativePath = "$($scope.Name)/$($relativeInScope.Replace('\', '/'))"
                bytes = $file.Length
                sha256 = $sourceHash
                sourcePath = $file.FullName
                copiedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
                affectsRecipesOrRegistry = [bool]$scope.AffectsRecipesOrRegistry
            }
            ($record | ConvertTo-Json -Compress) | Add-Content -LiteralPath $manifestPath -Encoding utf8
            if ($scope.Name -eq 'mods' -and $file.Extension -eq '.jar') {
                $metadata = Get-ModMetadata $file
                $inventory.Add([pscustomobject][ordered]@{
                    fileName = $file.Name
                    modIds = $metadata.modIds
                    declaredVersions = $metadata.declaredVersions
                    manifestVersion = $metadata.manifestVersion
                    sha256 = $sourceHash
                    serverRequired = 'present-in-pack-server;verify-by-load'
                    createRelated = $metadata.createRelated
                    recipeOrKubeJsRelated = $metadata.recipeOrKubeJsRelated
                    clientOnly = 'unknown-until-forge-load'
                    excluded = $false
                    exclusionBasis = ''
                    displayTest = $metadata.displayTest
                    metadataStatus = $metadata.metadataStatus
                })
            }
        }
    }
    $inventory | Export-Csv -LiteralPath (Join-Path $evidenceDirectory 'mod-inventory.csv') -NoTypeInformation -Encoding utf8
    $plan | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'profile-plan.json') -Encoding utf8
    @(
        'eula=true'
    ) | Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
    @(
        'online-mode=false'
        'server-port=0'
        'level-name=r09-world'
        'level-seed=steve-industrial-deceasedcraft-r09-v1'
        'level-type=minecraft:flat'
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
        'generate-structures=false'
        'view-distance=3'
        'simulation-distance=3'
        'max-players=1'
        'motd=Steve Industrial Agent R-09 isolated read-only knowledge probe'
    ) | Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ascii
    @(
        '-Xms2G'
        '-Xmx6G'
        '-Dterminal.jline=false'
        '-Dterminal.ansi=false'
    ) | Set-Content -LiteralPath (Join-Path $runDirectory 'user_jvm_args.txt') -Encoding ascii
    'steve-industrial:r09-isolated-profile/v1' |
        Set-Content -LiteralPath (Join-Path $runDirectory '.steve-industrial-r09-profile') -Encoding ascii

    foreach ($forbidden in $forbiddenNames) {
        $copiedForbidden = @(Get-ChildItem -LiteralPath $sourceCopy, $runDirectory -Force -Recurse -ErrorAction Stop |
            Where-Object Name -EQ $forbidden)
        if ($copiedForbidden.Count -gt 0) {
            Write-R09Failure 'EXTERNAL_WRITE_RISK' 'copy-postcondition' `
                    "Forbidden source content was copied: $($copiedForbidden.FullName -join ', ')" `
                    -NextStep 'Keep the evidence, delete only the ignored profile after review, and narrow the allowlist.'
        }
    }

    $after = [ordered]@{
        schema = 'steve-industrial:r09-external-snapshot/v1'
        client = Get-DirectorySnapshot $client 'formal-client'
        server = Get-DirectorySnapshot $server 'formal-dedicated-server'
    }
    $after | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'external-after.json') -Encoding utf8
    if ($before.client.fingerprint -ne $after.client.fingerprint -or
            $before.server.fingerprint -ne $after.server.fingerprint) {
        Write-R09Failure 'EXTERNAL_WRITE_RISK' 'external-after-compare' `
                "Formal source fingerprint changed during profile creation: client=$($before.client.fingerprint)->$($after.client.fingerprint) server=$($before.server.fingerprint)->$($after.server.fingerprint)" `
                -UserIntervention $true -NextStep 'Stop all R-09 launches and review the exact external snapshot difference; do not auto-repair D:\PCL2.'
    }
    $manifestCount = @(Get-Content -LiteralPath $manifestPath).Count
    $marker = "R09_PROFILE_BUILD_PASS gameDir=$runDirectory sourceFiles=$manifestCount modJars=$($inventory.Count) clientFingerprint=$($after.client.fingerprint) serverFingerprint=$($after.server.fingerprint) externalMutation=false savesOrWorldRead=false"
    $marker | Set-Content -LiteralPath $failureLog -Encoding utf8
    Write-Output $marker
    Write-Output "Profile evidence: $evidenceDirectory"
    Write-Output "Profile builder log: $failureLog"
} catch {
    if ($_.Exception.Message -like 'R09_FAILURE *') { throw }
    Write-R09Failure 'PACK_PROFILE_COPY_FAILED' 'profile-build' $_.Exception.Message `
            -NextStep 'Inspect the retained builder log and source manifest; retry only against the ignored profile.'
}
