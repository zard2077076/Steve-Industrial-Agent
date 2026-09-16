param(
    [string]$InstanceRoot
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$launcher = Join-Path $PSScriptRoot 'Start-IsolatedWritablePlayerInstance.ps1'
if ([string]::IsNullOrWhiteSpace($InstanceRoot)) {
    $InstanceRoot = Join-Path $repoRoot 'work\isolated-player\SteveAgent_DeceasedCraft_Test'
}
$resolvedInstance = (Resolve-Path -LiteralPath $InstanceRoot).Path
$gameDir = Join-Path $resolvedInstance 'run'

function Get-InstanceProcesses {
    return @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -like "*$gameDir*"
    })
}

$before = @(Get-InstanceProcesses)
$output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $launcher `
    -InstanceRoot $resolvedInstance -PlanOnly 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Windows PowerShell 5.1 launcher plan failed: $($output -join [Environment]::NewLine)"
}
$line = ($output -join [Environment]::NewLine)
if ($line -notmatch 'IWP_CLIENT_LAUNCH_PLAN_PASS' -or
        $line -notmatch 'commandLineBuilt=true' -or
        $line -notmatch 'powershellCompatible=5\.1\+' -or
        $line -notmatch 'inheritedClientJarIgnored=1\.20\.1\.jar' -or
        $line -notmatch 'threeModeBoundaryEnabled=true' -or
        $line -notmatch 'backupIdentity=iwp05-' -or
        $line -notmatch 'backupManifest=[0-9a-f]{64}' -or
        $line -notmatch 'accountOrTokenRead=false' -or
        $line -notmatch 'formalRootReadOnly=true') {
    throw "Windows PowerShell 5.1 launcher plan lacks required safety evidence: $line"
}
$after = @(Get-InstanceProcesses)
if ($after.Count -ne $before.Count) {
    throw 'Plan-only launcher validation unexpectedly changed the instance process count.'
}
Write-Output "IWP_CLIENT_LAUNCHER_COMPATIBILITY_PASS powershell=5.1 commandLineBuilt=true threeModeBoundaryEnabled=true processDelta=0 accountOrTokenRead=false formalRootReadOnly=true"
