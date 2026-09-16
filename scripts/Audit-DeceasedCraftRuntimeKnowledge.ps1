param(
    [string]$ClientRoot = 'D:\PCL2\.minecraft\versions\DeceasedCraft_Beta 5.10.16',
    [string]$ServerRoot = 'D:\PCL2\servers\DeceasedCraft_Server_Beta_5.10.16'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$logDirectory = Join-Path $repoRoot 'work\logs'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$log = Join-Path $logDirectory ("deceasedcraft-runtime-readonly-audit-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

function Resolve-AuditedRoot([string]$Path) {
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $allowed = (Resolve-Path -LiteralPath 'D:\PCL2').Path.TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Audit root is outside the read-only PCL2 boundary: $resolved"
    }
    return $resolved.TrimEnd('\')
}

function Get-ScopedFiles([string]$Root) {
    $scopes = @('mods', 'config', 'defaultconfigs', 'kubejs', 'generated_datapacks')
    foreach ($scope in $scopes) {
        $path = Join-Path $Root $scope
        if (Test-Path -LiteralPath $path) {
            Get-ChildItem -LiteralPath $path -File -Recurse -ErrorAction Stop
        }
    }
}

function Get-MetadataFingerprint([string]$Client, [string]$Server) {
    $lines = @(
        @(Get-ScopedFiles $Client | ForEach-Object {
            "client|$($_.FullName.Substring($Client.Length + 1))|$($_.Length)|$($_.LastWriteTimeUtc.Ticks)"
        })
        @(Get-ScopedFiles $Server | ForEach-Object {
            "server|$($_.FullName.Substring($Server.Length + 1))|$($_.Length)|$($_.LastWriteTimeUtc.Ticks)"
        })
    ) | Sort-Object
    $bytes = [Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return 'sha256:' + ([Convert]::ToHexString($sha.ComputeHash($bytes))).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-KubeJsHashes([string]$Root) {
    $kubeRoot = Join-Path $Root 'kubejs'
    return @(Get-ChildItem -LiteralPath $kubeRoot -File -Recurse | ForEach-Object {
        "$($_.FullName.Substring($kubeRoot.Length + 1))|$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash)"
    } | Sort-Object)
}

function Find-KubeJs([string]$Root, [string]$Pattern) {
    $kubeRoot = Join-Path $Root 'kubejs'
    return @(Get-ChildItem -LiteralPath $kubeRoot -File -Recurse |
        Select-String -Pattern $Pattern -CaseSensitive:$false -ErrorAction Stop)
}

$client = Resolve-AuditedRoot $ClientRoot
$server = Resolve-AuditedRoot $ServerRoot
$before = Get-MetadataFingerprint $client $server

$lines = [Collections.Generic.List[string]]::new()
$lines.Add("DECEASEDCRAFT_READONLY_AUDIT_BEGIN client=$client server=$server metadataBefore=$before")

$manifestPath = Join-Path $client ((Split-Path -Leaf $client) + '.json')
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$gameArgs = @($manifest.arguments.game | Where-Object { $_ -is [string] })
$forgeIndex = [Array]::IndexOf($gameArgs, '--fml.forgeVersion')
$forgeVersion = if ($forgeIndex -ge 0 -and $forgeIndex + 1 -lt $gameArgs.Count) { $gameArgs[$forgeIndex + 1] } else { 'unknown' }

$clientMods = @(Get-ChildItem -LiteralPath (Join-Path $client 'mods') -File)
$serverMods = @(Get-ChildItem -LiteralPath (Join-Path $server 'mods') -File)
$clientCreateMods = @($clientMods | Where-Object { $_.Name -match '(?i)create|flywheel|ponder' } | Sort-Object Name)
$serverCreateMods = @($serverMods | Where-Object { $_.Name -match '(?i)create|flywheel|ponder' } | Sort-Object Name)
$createJar = @($serverCreateMods | Where-Object { $_.Name -match '^create-1\.20\.1-' })

$lines.Add("RUNTIME_VERSIONS minecraft=$($manifest.inheritsFrom) forge=$forgeVersion createJars=$($createJar.Name -join ',') pack=$(Split-Path -Leaf $client)")
$lines.Add("MOD_COUNTS client=$($clientMods.Count) server=$($serverMods.Count) clientCreateRelated=$($clientCreateMods.Count) serverCreateRelated=$($serverCreateMods.Count)")
$lines.Add("SERVER_CREATE_RELATED names=$($serverCreateMods.Name -join ',')")

$clientKubeHashes = Get-KubeJsHashes $client
$serverKubeHashes = Get-KubeJsHashes $server
$allKubeDiff = @(Compare-Object $clientKubeHashes $serverKubeHashes)
$clientServerScripts = @($clientKubeHashes | Where-Object { $_ -like 'server_scripts\*' })
$serverServerScripts = @($serverKubeHashes | Where-Object { $_ -like 'server_scripts\*' })
$serverScriptDiff = @(Compare-Object $clientServerScripts $serverServerScripts)
$lines.Add("KUBEJS_MIRROR clientFiles=$($clientKubeHashes.Count) serverFiles=$($serverKubeHashes.Count) allDiffRows=$($allKubeDiff.Count) serverScriptDiffRows=$($serverScriptDiff.Count)")

$directMilling = Find-KubeJs $client 'recipes\.create\.milling\s*\('
$directPressing = Find-KubeJs $client 'recipes\.create\.pressing\s*\('
$sequencePressing = Find-KubeJs $client '\.createPressing\s*\('
$requiredCobbleId = Find-KubeJs $client 'create:milling/cobblestone'
$requiredIronId = Find-KubeJs $client 'create:pressing/iron_ingot'
$lines.Add("KUBEJS_CREATE_RECIPES directMilling=$($directMilling.Count) directPressing=$($directPressing.Count) sequencedPressingSteps=$($sequencePressing.Count) requiredCobblestoneIdMentions=$($requiredCobbleId.Count) requiredIronPressingIdMentions=$($requiredIronId.Count)")
$directRecipes = @($directMilling)
$directRecipes += @($directPressing)
foreach ($match in $directRecipes) {
    $relative = $match.Path.Substring((Join-Path $client 'kubejs').Length + 1)
    $lines.Add("KUBEJS_DIRECT_CREATE_RECIPE file=$relative line=$($match.LineNumber) text=$($match.Line.Trim())")
}

$generatedFiles = @(Get-ChildItem -LiteralPath (Join-Path $client 'generated_datapacks') -File -Recurse)
$generatedCreateRecipes = @($generatedFiles | Select-String -Pattern '"type"\s*:\s*"create:(milling|pressing)"' -CaseSensitive:$false -ErrorAction SilentlyContinue)
$createConfigs = @(Get-ChildItem -LiteralPath (Join-Path $client 'config') -File -Recurse | Where-Object { $_.Name -match '(?i)create|flywheel|ponder' })
$defaultCreateConfigs = @(Get-ChildItem -LiteralPath (Join-Path $client 'defaultconfigs') -File -Recurse | Where-Object { $_.Name -match '(?i)create' })
$lines.Add("CONFIG_AND_DATAPACKS createConfigFiles=$($createConfigs.Count) defaultCreateConfigFiles=$($defaultCreateConfigs.Count) generatedDatapackFiles=$($generatedFiles.Count) generatedMillingPressingRecipes=$($generatedCreateRecipes.Count)")

$beforeFinish = Get-MetadataFingerprint $client $server
if ($before -ne $beforeFinish) {
    throw "Read-only audit observed external metadata drift during the scan: before=$before after=$beforeFinish"
}
$lines.Add("DECEASEDCRAFT_READONLY_AUDIT_PASS metadataAfter=$beforeFinish externalMutation=false formalInstanceLaunched=false savesOrWorldRead=false")

$lines | Tee-Object -FilePath $log
Write-Output "Audit log: $log"
