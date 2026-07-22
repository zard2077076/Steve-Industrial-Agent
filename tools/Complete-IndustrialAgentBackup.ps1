param([Parameter(Mandatory = $true)][string]$RequestFile)

$ErrorActionPreference = 'Stop'
$utf8 = New-Object Text.UTF8Encoding($false)

function Fail([string]$Code) { throw "PORTABLE_BACKUP_FAILURE code=$Code" }
function Full([string]$Value) { return [IO.Path]::GetFullPath($Value).TrimEnd('\') }
function Is-Child([string]$Child, [string]$Parent) {
    $childPath = Full $Child; $parentPath = Full $Parent
    return $childPath.StartsWith($parentPath + '\', [StringComparison]::OrdinalIgnoreCase)
}
function Assert-NoReparse([string]$PathValue) {
    $current = Get-Item -LiteralPath $PathValue -Force
    while ($null -ne $current) {
        if (($current.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { Fail 'REPARSE_PATH_REFUSED' }
        $current = $current.Parent
    }
}
function Assert-NoGitAncestor([string]$PathValue) {
    $current = Get-Item -LiteralPath $PathValue -Force
    while ($null -ne $current) {
        if (Test-Path -LiteralPath (Join-Path $current.FullName '.git')) { Fail 'REPOSITORY_PATH_REFUSED' }
        $current = $current.Parent
    }
}
function Relative([string]$Base, [string]$PathValue) {
    $baseFull = (Full $Base) + '\'
    $baseUri = New-Object Uri($baseFull)
    $pathUri = New-Object Uri((Full $PathValue))
    return [Uri]::UnescapeDataString($baseUri.MakeRelativeUri($pathUri).ToString()).Replace('\','/')
}
function File-Hash([string]$PathValue) {
    $sha = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::OpenRead($PathValue)
    try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
    finally { $stream.Dispose(); $sha.Dispose() }
}
function Text-Hash([string]$Value) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($utf8.GetBytes($Value)))).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose() }
}
function Write-Utf8([string]$PathValue, [string]$Value) { [IO.File]::WriteAllText($PathValue, $Value, $utf8) }
function Manifest([string]$Root) {
    $rows = [Collections.Generic.List[object]]::new()
    foreach ($file in Get-ChildItem -LiteralPath $Root -File -Recurse | Sort-Object FullName) {
        $relative = Relative $Root $file.FullName
        if ($relative -eq 'session.lock' -or $relative -match '(?i)(\.tmp$|\.lck$|\.lock$)') { continue }
        $rows.Add([pscustomobject]@{ Relative=$relative; Bytes=$file.Length; Hash=(File-Hash $file.FullName) })
    }
    return @($rows | Sort-Object Relative)
}
function Manifest-Text($Rows) {
    $lines = [Collections.Generic.List[string]]::new(); $lines.Add("relativePath`tsizeBytes`tsha256")
    foreach ($row in $Rows) { $lines.Add("$($row.Relative)`t$($row.Bytes)`t$($row.Hash)") }
    return ($lines -join "`n") + "`n"
}
function Copy-Manifest($Rows, [string]$Source, [string]$Destination) {
    foreach ($row in $Rows) {
        $target = Join-Path $Destination $row.Relative.Replace('/','\')
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        Copy-Item -LiteralPath (Join-Path $Source $row.Relative.Replace('/','\')) -Destination $target
        if ((Get-Item -LiteralPath $target).Length -ne $row.Bytes -or (File-Hash $target) -ne $row.Hash) {
            Fail 'BACKUP_COPY_HASH_MISMATCH'
        }
    }
}

$requestPath = (Resolve-Path -LiteralPath $RequestFile).Path
Assert-NoReparse $requestPath
$request = Get-Content -LiteralPath $requestPath -Raw -Encoding utf8 | ConvertFrom-Json
if ($request.schema -ne 'steve-industrial:portable-backup-request/v1' -or
        $request.requestId -notmatch '^[0-9a-f-]{36}$' -or
        $request.worldIdentity -notmatch '^world:[0-9a-f]{64}$' -or
        $request.markerGeneration -notmatch '^[0-9a-f-]{36}$') { Fail 'REQUEST_SCHEMA_INVALID' }

$backupRoot = Full $request.backupRoot
$worldRoot = (Resolve-Path -LiteralPath $request.worldRoot).Path
$instanceRoot = (Resolve-Path -LiteralPath $request.testInstanceRoot).Path
$requestsRoot = Join-Path $backupRoot 'requests'
if (-not (Is-Child $requestPath $requestsRoot) -or (Split-Path -Leaf $requestPath) -ne "$($request.requestId).json") {
    Fail 'REQUEST_PATH_ESCAPE'
}
$savesRoot = Full (Join-Path $instanceRoot 'saves')
$worldRelative = Relative $savesRoot $worldRoot
if ([IO.Path]::IsPathRooted($worldRelative) -or $worldRelative -eq '..' -or
        $worldRelative.StartsWith('..\') -or $worldRelative.StartsWith('../')) {
    Fail 'WORLD_OUTSIDE_TEST_INSTANCE'
}
foreach ($formal in @($request.importantInstanceRoots)) {
    $formalRoot = (Resolve-Path -LiteralPath $formal).Path
    if ((Is-Child $instanceRoot $formalRoot) -or (Is-Child $worldRoot $formalRoot) -or
            (Is-Child $backupRoot $formalRoot) -or (Is-Child $formalRoot $backupRoot)) { Fail 'FORMAL_ROOT_OVERLAP' }
}
Assert-NoReparse $worldRoot; Assert-NoReparse $instanceRoot; Assert-NoReparse $backupRoot
Assert-NoGitAncestor $backupRoot

$processes = if (Get-Command Get-CimInstance -ErrorAction SilentlyContinue) {
    Get-CimInstance Win32_Process -ErrorAction Stop
} else {
    Get-WmiObject Win32_Process -ErrorAction Stop
}
$running = @($processes | Where-Object {
    $_.Name -match '^javaw?\.exe$' -and ($_.CommandLine -like "*$instanceRoot*" -or $_.CommandLine -like "*$worldRoot*")
})
if ($running.Count -gt 0) { Fail 'GAME_PROCESS_STILL_RUNNING' }
$lock = Join-Path $worldRoot 'session.lock'
if (Test-Path -LiteralPath $lock -PathType Leaf) {
    try { $stream=[IO.File]::Open($lock,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None); $stream.Dispose() }
    catch { Fail 'WORLD_SESSION_LOCKED' }
}

$preRows = Manifest $worldRoot
$preText = Manifest-Text $preRows
$preFingerprint = Text-Hash $preText
$totalBytes = [long](($preRows | Measure-Object Bytes -Sum).Sum)
$existing = Get-Item -LiteralPath $backupRoot
$drive = New-Object IO.DriveInfo($existing.PSDrive.Root)
if ($totalBytes -gt ([long]::MaxValue / 3L)) { Fail 'BACKUP_SIZE_OVERFLOW' }
$required = [Math]::Max(10485760L, ($totalBytes * 2L + [Math]::Max(4096L, [long]($totalBytes / 10L))))
if ($drive.AvailableFreeSpace -lt $required) { Fail 'INSUFFICIENT_DISK_SPACE' }

$worldKey = $request.worldIdentity.Substring(6)
$worldDirectory = Join-Path (Join-Path $backupRoot 'worlds') $worldKey
New-Item -ItemType Directory -Force -Path $worldDirectory | Out-Null
$recordId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '-' + $request.requestId
$final = Join-Path $worldDirectory $recordId
$staging = Join-Path $worldDirectory ('.staging-' + $recordId)
if ((Test-Path -LiteralPath $final) -or (Test-Path -LiteralPath $staging)) { Fail 'BACKUP_TARGET_EXISTS' }
New-Item -ItemType Directory -Force -Path (Join-Path $staging 'backup'),(Join-Path $staging 'restore-drill') | Out-Null
Copy-Manifest $preRows $worldRoot (Join-Path $staging 'backup')
Copy-Manifest $preRows (Join-Path $staging 'backup') (Join-Path $staging 'restore-drill')
Write-Utf8 (Join-Path $staging 'manifest.tsv') $preText
$manifestHash = Text-Hash $preText

$postRows = Manifest $worldRoot
$postFingerprint = Text-Hash (Manifest-Text $postRows)
if ($postFingerprint -ne $preFingerprint) { Fail 'SOURCE_CHANGED_DURING_BACKUP' }
$completion = [ordered]@{
    schema='steve-industrial:portable-backup/v1'; worldIdentity=$request.worldIdentity
    markerGeneration=$request.markerGeneration; requestId=$request.requestId
    manifestHash=$manifestHash; sourcePreFingerprint=$preFingerprint
    sourcePostFingerprint=$postFingerprint; totalFiles=$preRows.Count; totalBytes=$totalBytes
    completed=$true; backupVerified=$true; restoreDrillPass=$true; formalWorldTouched=$false
}
Write-Utf8 (Join-Path $staging 'completed.json') (($completion | ConvertTo-Json -Depth 5) + "`n")
Write-Utf8 (Join-Path $staging 'completed.marker') ("steve-industrial:portable-backup/v1`n$manifestHash`n")
Copy-Item -LiteralPath $requestPath -Destination (Join-Path $staging 'request.json')
Move-Item -LiteralPath $staging -Destination $final
$latest = [ordered]@{ schema='steve-industrial:portable-backup/v1'; relativeRecord=$recordId }
$latestTemp = Join-Path $worldDirectory ('.latest-' + $request.requestId + '.tmp')
Write-Utf8 $latestTemp (($latest | ConvertTo-Json) + "`n")
Move-Item -LiteralPath $latestTemp -Destination (Join-Path $worldDirectory 'latest.json') -Force
Remove-Item -LiteralPath $requestPath -Force
Write-Output "PORTABLE_BACKUP_COMPLETE id=backup-$manifestHash files=$($preRows.Count) bytes=$totalBytes sourcePrePostEqual=true restoreDrill=true formalWorldTouched=false automaticUpload=false"
