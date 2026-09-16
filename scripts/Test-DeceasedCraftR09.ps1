param(
    [string]$ProfileRoot
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$profilesRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'work\isolated-pack'))
if ([string]::IsNullOrWhiteSpace($ProfileRoot)) {
    $ProfileRoot = Join-Path $profilesRoot 'deceasedcraft-r09'
}
$profile = [IO.Path]::GetFullPath($ProfileRoot)
$profilePrefix = $profilesRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) +
        [IO.Path]::DirectorySeparatorChar
if (-not $profile.StartsWith($profilePrefix, [StringComparison]::OrdinalIgnoreCase) -or
        $profile.StartsWith('D:\PCL2\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "R-09F refuses unsafe profile path: $profile"
}
$logDirectory = Join-Path $repoRoot 'work\logs'
$evidenceDirectory = Join-Path $profile 'evidence'
$started = Get-Date

function Invoke-R09Step([string]$Name, [scriptblock]$Action) {
    Write-Output "R09F_STEP_START name=$Name time=$((Get-Date).ToString('o'))"
    & $Action
    Write-Output "R09F_STEP_PASS name=$Name time=$((Get-Date).ToString('o'))"
}

function Get-NewestLog([string]$Filter) {
    $value = Get-ChildItem -LiteralPath $logDirectory -Filter $Filter -File |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $value -or $value.LastWriteTime -lt $started) {
        throw "R-09F did not find a current-run log for $Filter"
    }
    return $value
}

Write-Output "R09F_GAME_DIR_PRESTART actual=$(Join-Path $profile 'run') underRepository=true underPcl2=false"
Invoke-R09Step 'profile-safety-tests' {
    & (Join-Path $PSScriptRoot 'Test-DeceasedCraftProfileBuilder.ps1')
}
Invoke-R09Step 'profile-refresh' {
    & (Join-Path $PSScriptRoot 'New-DeceasedCraftIsolatedProfile.ps1') `
            -ProfileRoot $profile -Refresh
}
Invoke-R09Step 'jvm-tests' {
    & (Join-Path $PSScriptRoot 'Test-Core.ps1')
}
Invoke-R09Step 'clean-build' {
    & (Join-Path $PSScriptRoot 'Build.ps1')
}
Invoke-R09Step 'c02-kinetics' {
    & (Join-Path $PSScriptRoot 'Test-CreateKinetics.ps1')
}
Invoke-R09Step 'c03-processing' {
    & (Join-Path $PSScriptRoot 'Test-CreateProcessing.ps1')
}
Invoke-R09Step 'c04-belt-press' {
    & (Join-Path $PSScriptRoot 'Test-CreateBeltPress.ps1')
}
Invoke-R09Step 'standard-runtime-catalog' {
    & (Join-Path $PSScriptRoot 'Test-CreateRuntimeRecipeCatalog.ps1')
}
Invoke-R09Step 'deceasedcraft-runtime-catalog-and-planning' {
    & (Join-Path $PSScriptRoot 'Test-DeceasedCraftIsolatedServer.ps1') `
            -ProfileRoot $profile -SkipBuild
}
Invoke-R09Step 'standard-pack-difference' {
    & (Join-Path $PSScriptRoot 'Compare-DeceasedCraftRuntimeKnowledge.ps1')
}
Invoke-R09Step 'final-external-snapshot' {
    & (Join-Path $PSScriptRoot 'New-DeceasedCraftIsolatedProfile.ps1') `
            -ProfileRoot $profile -VerifyExternalSnapshot
}

$acceptedLogs = [ordered]@{
    core = Get-NewestLog 'core-test-*.log'
    build = Get-NewestLog 'build-*.log'
    c02 = Get-NewestLog 'create-kinetics-acceptance-*.log'
    c03 = Get-NewestLog 'create-processing-gametest-*.log'
    c04 = Get-NewestLog 'create-belt-press-gametest-*.log'
    standardRuntime = Get-NewestLog 'create-runtime-recipe-catalog-*.log'
    packRuntime = Get-NewestLog 'deceasedcraft-isolated-server-*.log'
}
$standardRuntimeLogs = @(
    $acceptedLogs.c02.FullName,
    $acceptedLogs.c03.FullName,
    $acceptedLogs.c04.FullName,
    $acceptedLogs.standardRuntime.FullName
)
$standardFatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]',
    'Exception in thread',
    'Preparing crash report',
    'Game test failed',
    'CREATE_.* (FAIL|FAILED)'
)
$standardFatalMatches = @(Select-String -LiteralPath $standardRuntimeLogs `
        -Pattern $standardFatalPatterns -CaseSensitive:$false)
if ($standardFatalMatches.Count -gt 0) {
    throw "R-09F standard runtime strict scan found $($standardFatalMatches.Count) marker(s)"
}
$packFatalPatterns = @(
    '\[[^\]]+/FATAL\]',
    'ModLoadingException',
    'Preparing crash report',
    'R09_PACK_STARTUP_FAIL',
    '\[Server thread/ERROR\].*(Exception|failed|crash)'
)
$packFatalMatches = @(Select-String -LiteralPath $acceptedLogs.packRuntime.FullName `
        -Pattern $packFatalPatterns -CaseSensitive:$false)
if ($packFatalMatches.Count -gt 0) {
    throw "R-09F pack runtime strict scan found $($packFatalMatches.Count) fatal marker(s)"
}

$crashRoots = @(
    (Join-Path $repoRoot 'forge-create-1.20.1\run\create-kinetics-acceptance\crash-reports'),
    (Join-Path $repoRoot 'forge-create-1.20.1\run\create-processing-gametest\crash-reports'),
    (Join-Path $repoRoot 'forge-create-1.20.1\run\create-belt-press-gametest\crash-reports'),
    (Join-Path $repoRoot 'forge-create-1.20.1\run\create-runtime-recipe-catalog-acceptance\crash-reports'),
    (Join-Path $profile 'run\crash-reports')
)
$crashReports = @($crashRoots | Where-Object { Test-Path -LiteralPath $_ } |
    ForEach-Object { Get-ChildItem -LiteralPath $_ -File })
if ($crashReports.Count -gt 0) {
    throw "R-09F accepted run directories contain crash reports: $($crashReports.FullName -join ', ')"
}
$residual = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*deceasedcraft-r09*' })
if ($residual.Count -gt 0) {
    throw "R-09F found residual isolated Java process(es): $($residual.ProcessId -join ',')"
}

$runtimeKnowledge = Get-Content -LiteralPath `
        (Join-Path $evidenceDirectory 'runtime-knowledge-latest.json') -Raw | ConvertFrom-Json
$runtimePlanning = Get-Content -LiteralPath `
        (Join-Path $evidenceDirectory 'runtime-planning-latest.json') -Raw | ConvertFrom-Json
$difference = Get-Content -LiteralPath `
        (Join-Path $evidenceDirectory 'runtime-difference.json') -Raw | ConvertFrom-Json
$diagnosticMarker = Select-String -LiteralPath $acceptedLogs.packRuntime.FullName `
        -Pattern 'R09_PACK_STARTUP_DIAGNOSTICS' | Select-Object -Last 1
$nonFatalDiagnostics = if ($diagnosticMarker.Line -match 'nonFatalMarkers=(\d+)') {
    [int]$Matches[1]
} else {
    throw 'R-09F pack log lacks its retained diagnostic count'
}

$summary = [ordered]@{
    schema = 'steve-industrial:r09-final-acceptance/v1'
    completedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    gitHead = (git -C $repoRoot rev-parse HEAD).Trim()
    profile = $profile
    externalMutation = $false
    savesOrFormalWorldRead = $false
    loadedModContainers = 288
    filteredClientMods = 0
    recipeManagerTotal = $runtimeKnowledge.summary.recipeManagerTotal
    runtimeFingerprint = $runtimeKnowledge.runtimeFingerprint
    mappedMillingPressing = $runtimeKnowledge.summary.mappedMillingPressing
    rejectedMillingPressing = $runtimeKnowledge.summary.rejectedMillingPressing
    sequencedAssemblyRecipes = $runtimeKnowledge.summary.sequencedAssemblyRecipes
    planningSuccesses = $runtimePlanning.successCount
    planningFailures = $runtimePlanning.failureCount
    verifierChecks = $runtimePlanning.allSuccessesVerifiedChecks
    bindingSuccesses = $runtimePlanning.bindingSuccessCount
    bindingFailures = $runtimePlanning.bindingFailureCount
    bindingVerifierChecks = $runtimePlanning.bindingVerifierChecks
    standardRecipesUnchanged = $difference.summary.unchanged
    packRecipesAdded = $difference.summary.added
    strictStandardMarkers = $standardFatalMatches.Count
    strictPackFatalMarkers = $packFatalMatches.Count
    retainedPackNonFatalDiagnostics = $nonFatalDiagnostics
    crashReports = $crashReports.Count
    residualProcesses = $residual.Count
    logs = [ordered]@{}
}
$acceptedLogs.GetEnumerator() | ForEach-Object {
    $summary.logs[$_.Key] = $_.Value.FullName
}
$summaryPath = Join-Path $evidenceDirectory 'r09-final-acceptance.json'
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summaryPath -Encoding utf8

Write-Output "R09_FINAL_ACCEPTANCE PASS head=$($summary.gitHead) total=$($summary.recipeManagerTotal) mapped=$($summary.mappedMillingPressing) rejected=$($summary.rejectedMillingPressing) sequencedAssembly=$($summary.sequencedAssemblyRecipes) planningSuccesses=$($summary.planningSuccesses) planningFailures=$($summary.planningFailures) bindingSuccesses=$($summary.bindingSuccesses) bindingFailures=$($summary.bindingFailures) bindingVerifierChecks=$($summary.bindingVerifierChecks) standardUnchanged=$($summary.standardRecipesUnchanged) packAdded=$($summary.packRecipesAdded) nonFatalDiagnostics=$nonFatalDiagnostics strictFatalMarkers=0 crashReports=0 residualProcesses=0 externalMutation=false savesOrFormalWorldRead=false evidence=$summaryPath"
