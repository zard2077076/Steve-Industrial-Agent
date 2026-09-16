$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$builder = Join-Path $PSScriptRoot 'New-DeceasedCraftIsolatedProfile.ps1'
$safeProfile = Join-Path $repoRoot 'work\isolated-pack\deceasedcraft-r09-safety-test'

function Assert-Rejected([string]$ExpectedCode, [scriptblock]$Action) {
    try {
        & $Action
        throw "Expected typed rejection $ExpectedCode"
    } catch {
        if ($_.Exception.Message -notmatch ("code=" + [regex]::Escape($ExpectedCode))) {
            throw "Expected typed rejection $ExpectedCode but received: $($_.Exception.Message)"
        }
    }
}

$planOutput = @(& $builder -ProfileRoot $safeProfile -PlanOnly)
if (($planOutput -join "`n") -notmatch 'R09_PROFILE_PLAN_PASS copied=false externalMutation=false savesOrWorldRead=false') {
    throw 'Safe ignored profile did not pass plan-only validation'
}
if (Test-Path -LiteralPath $safeProfile) {
    throw 'Plan-only validation unexpectedly created the isolated profile'
}

Assert-Rejected 'EXTERNAL_WRITE_RISK' {
    & $builder -ProfileRoot (Join-Path $repoRoot 'unsafe-r09-profile') -PlanOnly
}
Assert-Rejected 'EXTERNAL_WRITE_RISK' {
    & $builder -ProfileRoot 'D:\PCL2\r09-unsafe-profile' -PlanOnly
}

$sourceText = Get-Content -Raw -LiteralPath $builder
foreach ($required in @('savesOrWorldRead = $false', "'saves', 'world'", 'git -C $repoRoot check-ignore')) {
    if (-not $sourceText.Contains($required, [StringComparison]::Ordinal)) {
        throw "Builder is missing required safety boundary: $required"
    }
}

Write-Output 'R09_PROFILE_BUILDER_SAFETY PASS safeGameDir=true ignored=true planOnlyNoWrite=true outsideWorkRejected=true pcl2GameDirRejected=true forbiddenScopesExplicit=true savesOrWorldRead=false'
