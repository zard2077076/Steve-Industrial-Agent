$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$base = Join-Path ([IO.Path]::GetTempPath()) ('SteveIndustrialAgent-BackupTest-' + [guid]::NewGuid())
$instance = Join-Path $base 'instance'
$world = Join-Path $instance 'saves\Portable Backup Test'
$backup = Join-Path $base 'backups'
$formal = Join-Path $base 'important-instance'
$requests = Join-Path $backup 'requests'
New-Item -ItemType Directory -Force -Path $world,$requests,$formal | Out-Null
try {
    'level' | Set-Content -LiteralPath (Join-Path $world 'level.dat') -Encoding ascii
    New-Item -ItemType Directory -Force -Path (Join-Path $world 'data') | Out-Null
    'marker' | Set-Content -LiteralPath (Join-Path $world 'data\marker.dat') -Encoding ascii
    '' | Set-Content -LiteralPath (Join-Path $world 'session.lock') -Encoding ascii
    $requestId = [guid]::NewGuid().ToString()
    $worldIdentity = 'world:' + ('1' * 64)
    $generation = [guid]::NewGuid().ToString()
    $request = [ordered]@{
        schema='steve-industrial:portable-backup-request/v1'; requestId=$requestId
        worldIdentity=$worldIdentity; markerGeneration=$generation
        testInstanceRoot=$instance; worldRoot=$world; backupRoot=$backup
        importantInstanceRoots=@($formal); createdAtUtc=(Get-Date).ToUniversalTime().ToString('o')
        status='PENDING_GAME_SHUTDOWN'
    }
    $requestPath = Join-Path $requests ($requestId + '.json')
    [IO.File]::WriteAllText($requestPath, ($request | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))
    $output = & (Join-Path $repo 'tools\Complete-IndustrialAgentBackup.ps1') -RequestFile $requestPath
    if (($output -join "`n") -notmatch 'PORTABLE_BACKUP_COMPLETE') { throw 'PORTABLE_BACKUP_HELPER_DID_NOT_COMPLETE' }
    $worldKey = $worldIdentity.Substring(6)
    $pointer = Get-Content -LiteralPath (Join-Path $backup "worlds\$worldKey\latest.json") -Raw | ConvertFrom-Json
    $record = Join-Path $backup "worlds\$worldKey\$($pointer.relativeRecord)"
    $completion = Get-Content -LiteralPath (Join-Path $record 'completed.json') -Raw | ConvertFrom-Json
    if (-not $completion.completed -or -not $completion.backupVerified -or
            -not $completion.restoreDrillPass -or $completion.formalWorldTouched -or
            $completion.sourcePreFingerprint -ne $completion.sourcePostFingerprint -or
            $completion.markerGeneration -ne $generation) { throw 'PORTABLE_BACKUP_COMPLETION_INVALID' }
    $manifest = Get-Content -LiteralPath (Join-Path $record 'manifest.tsv')
    if ($manifest.Count -ne 3 -or $manifest[0] -ne "relativePath`tsizeBytes`tsha256") {
        throw 'PORTABLE_BACKUP_MANIFEST_INVALID'
    }
    foreach ($relative in @('level.dat','data/marker.dat')) {
        $sourceHash = (Get-FileHash -LiteralPath (Join-Path $world $relative.Replace('/','\')) -Algorithm SHA256).Hash
        $backupHash = (Get-FileHash -LiteralPath (Join-Path $record ('backup\' + $relative.Replace('/','\'))) -Algorithm SHA256).Hash
        $restoreHash = (Get-FileHash -LiteralPath (Join-Path $record ('restore-drill\' + $relative.Replace('/','\'))) -Algorithm SHA256).Hash
        if ($sourceHash -ne $backupHash -or $sourceHash -ne $restoreHash) { throw 'PORTABLE_BACKUP_READBACK_MISMATCH' }
    }
    if (Test-Path -LiteralPath $requestPath) { throw 'PORTABLE_BACKUP_PENDING_REQUEST_NOT_ARCHIVED' }
    Write-Output "PORTABLE_BACKUP_HELPER PASS files=$($completion.totalFiles) bytes=$($completion.totalBytes) sourcePrePostEqual=true restoreDrill=true overwrite=false formalWorldTouched=false"
} finally {
    if (Test-Path -LiteralPath $base) {
        $resolved = (Resolve-Path -LiteralPath $base).Path
        if (-not $resolved.StartsWith([IO.Path]::GetTempPath(), [StringComparison]::OrdinalIgnoreCase)) {
            throw "Unsafe portable-backup test cleanup target: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
