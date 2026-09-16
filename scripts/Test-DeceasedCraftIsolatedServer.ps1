param(
    [string]$ProfileRoot,
    [int]$StartupTimeoutSeconds = 180,
    [int]$InitializationTimeoutSeconds = 900,
    [int]$PlanningTimeoutSeconds = 180,
    [int]$ShutdownTimeoutSeconds = 120,
    [switch]$SkipBuild,
    [switch]$ExecutionPilot
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$profilesRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'work\isolated-pack'))
if ([string]::IsNullOrWhiteSpace($ProfileRoot)) {
    $ProfileRoot = Join-Path $profilesRoot 'deceasedcraft-r09'
}
$profile = [IO.Path]::GetFullPath($ProfileRoot)
$runDirectory = [IO.Path]::GetFullPath((Join-Path $profile 'run'))
$sourceDirectory = [IO.Path]::GetFullPath((Join-Path $profile 'source'))
$evidenceDirectory = [IO.Path]::GetFullPath((Join-Path $profile 'evidence'))
$logDirectory = Join-Path $repoRoot 'work\logs'
$started = Get-Date
$stamp = $started.ToString('yyyyMMdd-HHmmss')
$log = Join-Path $logDirectory "deceasedcraft-isolated-server-$stamp.log"
$stdout = Join-Path $evidenceDirectory "server-$stamp.stdout.log"
$stderr = Join-Path $evidenceDirectory "server-$stamp.stderr.log"
$failureCode = $null
$failureDetail = $null
$process = $null
$requiredPassMarker = if ($ExecutionPilot) { 'R09_EXECUTION_PILOT_PASS' } else { 'R09_PACK_STARTUP_PASS' }

function Write-R09Failure(
        [string]$Code,
        [string]$Stage,
        [string]$Detail,
        [string]$RelatedMod = 'none',
        [string]$Fingerprint = 'unavailable',
        [bool]$UserIntervention = $false,
        [string]$NextStep = 'Inspect the retained isolated-server log and change only the disposable profile.') {
    $line = "R09_FAILURE code=$Code stage=$Stage gameDir=$runDirectory mod=$RelatedMod log=$log fingerprint=$Fingerprint userIntervention=$($UserIntervention.ToString().ToLowerInvariant()) next=`"$NextStep`" detail=`"$Detail`""
    $line | Add-Content -LiteralPath $log -Encoding utf8
    throw $line
}

function Assert-SafeChildPath([string]$Path, [string]$Parent, [string]$Description) {
    $pathValue = [IO.Path]::GetFullPath($Path)
    $parentValue = [IO.Path]::GetFullPath($Parent)
    $prefix = $parentValue.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $pathValue.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -or
            $pathValue -eq $parentValue) {
        Write-R09Failure 'WRONG_GAME_DIRECTORY' 'preflight' `
                "$Description must be a strict child of $parentValue; actual=$pathValue" `
                -UserIntervention $true -NextStep 'Use only the ignored repository R-09 profile.'
    }
}

function Read-LocalProperties {
    $values = @{}
    $path = Join-Path $repoRoot 'local.properties'
    if (Test-Path -LiteralPath $path) {
        foreach ($line in Get-Content -LiteralPath $path) {
            if ($line -match '^\s*([^#][^=]*)=(.*)$') {
                $values[$Matches[1].Trim()] = $Matches[2].Trim()
            }
        }
    }
    return $values
}

function Get-CurrentOutput {
    $parts = [Collections.Generic.List[string]]::new()
    if (Test-Path -LiteralPath $stdout) { $parts.Add((Get-Content -Raw -LiteralPath $stdout)) }
    if (Test-Path -LiteralPath $stderr) { $parts.Add((Get-Content -Raw -LiteralPath $stderr)) }
    return $parts -join "`n--- STDERR ---`n"
}

function Stop-BoundedProcess([Diagnostics.Process]$Target) {
    if ($null -ne $Target) {
        $Target.Refresh()
        if (-not $Target.HasExited) {
            $Target.Kill($true)
            if (-not $Target.WaitForExit($ShutdownTimeoutSeconds * 1000)) {
                throw "Java process did not terminate after Kill(entireProcessTree=true): pid=$($Target.Id)"
            }
        }
    }
}

foreach ($bound in @($StartupTimeoutSeconds, $InitializationTimeoutSeconds,
        $PlanningTimeoutSeconds, $ShutdownTimeoutSeconds)) {
    if ($bound -lt 1 -or $bound -gt 3600) {
        throw 'Every R-09 process timeout must be between 1 and 3600 seconds'
    }
}
Assert-SafeChildPath $profile $profilesRoot 'profile root'
Assert-SafeChildPath $runDirectory $profile 'writable gameDir'
Assert-SafeChildPath $sourceDirectory $profile 'retained source copy'
if ($runDirectory.StartsWith('D:\PCL2\', [StringComparison]::OrdinalIgnoreCase)) {
    Write-R09Failure 'WRONG_GAME_DIRECTORY' 'preflight' `
            "Resolved writable gameDir is inside D:\PCL2: $runDirectory" `
            -UserIntervention $true -NextStep 'Rebuild the default ignored repository profile.'
}
if (-not (Test-Path -LiteralPath (Join-Path $runDirectory '.steve-industrial-r09-profile'))) {
    Write-R09Failure 'PACK_PROFILE_SOURCE_NOT_FOUND' 'preflight' `
            'R-09 isolated marker is missing' -NextStep 'Run New-DeceasedCraftIsolatedProfile.ps1 -Refresh first.'
}
New-Item -ItemType Directory -Force -Path $logDirectory, $evidenceDirectory | Out-Null
"R09_GAME_DIR_PRESTART actual=$runDirectory expected=$runDirectory underRepository=true underPcl2=false marker=true" |
    Set-Content -LiteralPath $log -Encoding utf8
Write-Output "R09_GAME_DIR_PRESTART actual=$runDirectory expected=$runDirectory underRepository=true underPcl2=false marker=true"
Write-Output "R09_TIMEOUTS startup=$StartupTimeoutSeconds initialization=$InitializationTimeoutSeconds planning=$PlanningTimeoutSeconds shutdown=$ShutdownTimeoutSeconds"

$builder = Join-Path $PSScriptRoot 'New-DeceasedCraftIsolatedProfile.ps1'
& $builder -ProfileRoot $profile -VerifyExternalSnapshot *>&1 | Add-Content -LiteralPath $log -Encoding utf8
$deploymentMarker = Join-Path $runDirectory '.steve-industrial-deployment-dry-run'
'steve-industrial:deployment-dry-run/v1' |
    Set-Content -LiteralPath $deploymentMarker -Encoding ascii
$executionMarker = Join-Path $runDirectory '.steve-industrial-execution-test'
if ($ExecutionPilot) {
    'steve-industrial:isolated-execution/v1' |
        Set-Content -LiteralPath $executionMarker -Encoding ascii
    $emptyPauseConfig = Join-Path $runDirectory 'config\readyplayerfun-common.toml'
    if (-not (Test-Path -LiteralPath $emptyPauseConfig -PathType Leaf)) {
        Write-R09Failure 'PACK_PROFILE_SOURCE_NOT_FOUND' 'execution-pilot-preflight' `
                'The isolated readyplayerfun empty-server pause config is missing' `
                -NextStep 'Rebuild only the isolated run copy from the retained source.'
    }
    $emptyPauseText = Get-Content -Raw -LiteralPath $emptyPauseConfig
    if ($emptyPauseText -notmatch 'pauseWhileEmptySeconds\s*=\s*(60|360)') {
        Write-R09Failure 'PACK_PROFILE_COPY_FAILED' 'execution-pilot-preflight' `
                'The isolated empty-server pause value is neither the verified source default nor the pilot override' `
                -NextStep 'Inspect the copied config before changing the isolated pilot override.'
    }
    $emptyPauseText -replace 'pauseWhileEmptySeconds\s*=\s*(60|360)', 'pauseWhileEmptySeconds = 360' |
        Set-Content -LiteralPath $emptyPauseConfig -Encoding utf8
    "R09_EXECUTION_PILOT_EMPTY_PAUSE_OVERRIDE sourceDefault=60 isolatedRun=360 formalSourceMutation=false" |
        Add-Content -LiteralPath $log -Encoding utf8
} elseif (Test-Path -LiteralPath $executionMarker) {
    Remove-Item -LiteralPath $executionMarker -Force
}

$local = Read-LocalProperties
if (-not $local.ContainsKey('java_home')) {
    Write-R09Failure 'FORGE_STARTUP_FAILED' 'java-preflight' `
            'Ignored local.properties does not configure java_home' -UserIntervention $true `
            -NextStep 'Configure the existing Java 17 JDK in ignored local.properties.'
}
$java = Join-Path $local['java_home'] 'bin\java.exe'
$javaVersion = & $java -version 2>&1
if ($LASTEXITCODE -ne 0 -or ($javaVersion -join ' ') -notmatch 'version "17\.') {
    Write-R09Failure 'FORGE_STARTUP_FAILED' 'java-preflight' `
            "R-09 requires Java 17; actual=$($javaVersion -join ' ')" -UserIntervention $true `
            -NextStep 'Point ignored java_home at the verified Java 17 JDK.'
}

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot 'Build.ps1')
}
$libsDirectory = Join-Path $repoRoot 'forge-create-1.20.1\build\libs'
$productionJars = @(Get-ChildItem -LiteralPath $libsDirectory -Filter '*.jar' -File |
    Where-Object Name -NotLike '*-sources.jar')
if ($productionJars.Count -ne 1) {
    Write-R09Failure 'PACK_PROFILE_COPY_FAILED' 'production-jar-selection' `
            "Expected exactly one clean-build production JAR; found=$($productionJars.Count)" `
            -NextStep 'Run scripts/Build.ps1 and inspect forge-create-1.20.1/build/libs.'
}
$productionJar = $productionJars[0]
$productionHash = (Get-FileHash -LiteralPath $productionJar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$sourceInventory = Import-Csv -LiteralPath (Join-Path $evidenceDirectory 'mod-inventory.csv')
if (@($sourceInventory | Where-Object { $_.modIds -split ',' -contains 'steve_create_agent' }).Count -gt 0) {
    Write-R09Failure 'MOD_VERSION_CONFLICT' 'production-jar-install' `
            'The retained pack source unexpectedly already contains Steve Industrial Agent' `
            -NextStep 'Do not overwrite it; inspect the retained source inventory.'
}
$installedJar = Join-Path (Join-Path $runDirectory 'mods') $productionJar.Name
Get-ChildItem -LiteralPath (Join-Path $runDirectory 'mods') -File -Filter 'steve-create-agent-forge-*.jar' |
    Remove-Item -Force
Copy-Item -LiteralPath $productionJar.FullName -Destination $installedJar
$installedHash = (Get-FileHash -LiteralPath $installedJar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($installedHash -ne $productionHash) {
    Write-R09Failure 'PACK_PROFILE_COPY_FAILED' 'production-jar-install' `
            'Installed production JAR hash differs from the clean build' `
            -NextStep 'Delete only the isolated run copy and reinstall from a clean build.'
}
$sourceJarLeak = @(Get-ChildItem -LiteralPath $sourceDirectory -File -Recurse -Filter $productionJar.Name)
if ($sourceJarLeak.Count -gt 0) {
    Write-R09Failure 'EXTERNAL_WRITE_RISK' 'production-jar-install' `
            'Production JAR leaked into the retained source copy' `
            -NextStep 'Stop and rebuild the isolated profile; never install into the formal source.'
}
$buildEvidence = [ordered]@{
    schema = 'steve-industrial:r09-production-jar/v1'
    jarPath = $productionJar.FullName
    installedPath = $installedJar
    sha256 = $productionHash
    gitHead = (git -C $repoRoot rev-parse HEAD).Trim()
    buildTimeUtc = $productionJar.LastWriteTimeUtc.ToString('o')
    minecraft = '1.20.1'
    forgeTarget = '47.4.x'
    createTarget = '6.0.6'
}
$buildEvidence | ConvertTo-Json -Depth 4 |
    Set-Content -LiteralPath (Join-Path $evidenceDirectory 'production-jar.json') -Encoding utf8

$worldDirectory = Join-Path $runDirectory 'r09-world'
Assert-SafeChildPath $worldDirectory $runDirectory 'isolated test world'
if (Test-Path -LiteralPath $worldDirectory) {
    Remove-Item -LiteralPath $worldDirectory -Recurse -Force
}
foreach ($generated in @('logs', 'crash-reports')) {
    $path = Join-Path $runDirectory $generated
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
}
$runtimeEvidenceDirectory = Join-Path $runDirectory 'r09-evidence'
if (Test-Path -LiteralPath $runtimeEvidenceDirectory) {
    Remove-Item -LiteralPath $runtimeEvidenceDirectory -Recurse -Force
}
$jvmArguments = @(
    '-Xms2G'
    '-Xmx6G'
    '-Dterminal.jline=false'
    '-Dterminal.ansi=false'
    '-Dsteve_industrial.r09.packProfile=true'
    ('"-Dsteve_industrial.r09.expectedGameDir={0}"' -f $runDirectory.Replace('\', '/'))
    '"-Dsteve_industrial.r09.forbiddenRoot=D:/PCL2"'
    ('"-Dsteve_industrial.deployment.expectedGameDir={0}"' -f $runDirectory.Replace('\', '/'))
    '"-Dsteve_industrial.deployment.forbiddenRoot=D:/PCL2"'
)
if ($ExecutionPilot) {
    $jvmArguments += '-Dsteve_industrial.r09.executionPilot=true'
    $jvmArguments += ('"-Dsteve_industrial.execution.expectedGameDir={0}"' -f $runDirectory.Replace('\', '/'))
    $jvmArguments += '"-Dsteve_industrial.execution.forbiddenRoot=D:/PCL2"'
}
$jvmArguments | Set-Content -LiteralPath (Join-Path $runDirectory 'user_jvm_args.txt') -Encoding ascii

$forgeArgs = 'libraries/net/minecraftforge/forge/1.20.1-47.4.0/win_args.txt'
if (-not (Test-Path -LiteralPath (Join-Path $runDirectory $forgeArgs))) {
    Write-R09Failure 'PACK_PROFILE_SOURCE_NOT_FOUND' 'forge-launch-metadata' `
            "Copied Forge launch metadata is missing: $forgeArgs" `
            -NextStep 'Rebuild the isolated profile from the existing dedicated-server source.'
}

try {
    $process = Start-Process -FilePath $java `
            -ArgumentList @('@user_jvm_args.txt', "@$forgeArgs", '--nogui') `
            -WorkingDirectory $runDirectory `
            -RedirectStandardOutput $stdout `
            -RedirectStandardError $stderr `
            -WindowStyle Hidden `
            -PassThru
    $processStartedAt = Get-Date
    "R09_PROCESS_STARTED pid=$($process.Id) java=$java gameDir=$runDirectory" |
        Add-Content -LiteralPath $log -Encoding utf8
    $forgeSeen = $false
    $knowledgeSeen = $false
    $planningStartedAt = $null
    $passSeen = $false
    $passAt = $null
    while ($true) {
        $process.Refresh()
        $output = Get-CurrentOutput
        if (-not $forgeSeen -and $output -match 'Forge mod loading, version 47\.4\.0, for MC 1\.20\.1') {
            $forgeSeen = $true
        }
        if (-not $passSeen -and $output -match $requiredPassMarker) {
            $passSeen = $true
            $passAt = Get-Date
        }
        if (-not $knowledgeSeen -and $output -match 'R09_PACK_RUNTIME_KNOWLEDGE_PASS') {
            $knowledgeSeen = $true
            $planningStartedAt = Get-Date
        }
        if ($process.HasExited) { break }
        # Build/profile preparation is intentionally outside the bounded server lifecycle.
        $elapsed = ((Get-Date) - $processStartedAt).TotalSeconds
        if (-not $forgeSeen -and $elapsed -gt $StartupTimeoutSeconds) {
            $failureCode = 'FORGE_STARTUP_FAILED'
            $failureDetail = "Forge launch marker exceeded startup timeout $StartupTimeoutSeconds seconds"
            Stop-BoundedProcess $process
            break
        }
        if (-not $knowledgeSeen -and $elapsed -gt $InitializationTimeoutSeconds) {
            $failureCode = 'RUNTIME_PROBE_TIMEOUT'
            $failureDetail = "ServerStarted/RecipeManager probe exceeded initialization timeout $InitializationTimeoutSeconds seconds"
            Stop-BoundedProcess $process
            break
        }
        if ($knowledgeSeen -and -not $passSeen -and
                ((Get-Date) - $planningStartedAt).TotalSeconds -gt $PlanningTimeoutSeconds) {
            $failureCode = 'RUNTIME_PROBE_TIMEOUT'
            $failureDetail = "Pack-backed planning exceeded planning timeout $PlanningTimeoutSeconds seconds"
            Stop-BoundedProcess $process
            break
        }
        if ($passSeen -and ((Get-Date) - $passAt).TotalSeconds -gt $ShutdownTimeoutSeconds) {
            $failureCode = 'PROCESS_DID_NOT_EXIT'
            $failureDetail = "Server did not exit within $ShutdownTimeoutSeconds seconds after the startup probe"
            Stop-BoundedProcess $process
            break
        }
        Start-Sleep -Milliseconds 500
    }
    $process.WaitForExit()
    $output = Get-CurrentOutput
    @(
        "R09_PRODUCTION_JAR name=$($productionJar.Name) sha256=$productionHash gitHead=$($buildEvidence.gitHead) minecraft=1.20.1 forgeTarget=47.4.x createTarget=6.0.6"
        $output
    ) | Add-Content -LiteralPath $log -Encoding utf8
    if ($null -eq $failureCode -and -not $passSeen) {
        if ($output -match 'R09_EXECUTION_PILOT_FAIL code=([A-Z_]+)') {
            $failureCode = $Matches[1]
        } elseif ($output -match 'R09_PACK_STARTUP_FAIL code=([A-Z_]+)') {
            $failureCode = $Matches[1]
        } elseif ($output -match '(?i)missing or unsupported mandatory dependencies|requires .+ currently') {
            $failureCode = 'MOD_DEPENDENCY_MISSING'
        } elseif ($output -match '(?i)version conflict|incompatible mod set|not compatible') {
            $failureCode = 'MOD_VERSION_CONFLICT'
        } elseif ($output -match '(?i)invalid dist|client-only|attempted to load class .+ for invalid dist') {
            $failureCode = 'CLIENT_ONLY_MOD_PRESENT'
        } else {
            $failureCode = 'FORGE_STARTUP_FAILED'
        }
        $failureDetail = "Isolated Forge exited without $requiredPassMarker; exit=$($process.ExitCode)"
    }
    if ($null -eq $failureCode -and $process.ExitCode -ne 0) {
        $failureCode = 'PROCESS_DID_NOT_EXIT'
        $failureDetail = "Isolated Forge returned nonzero exit code $($process.ExitCode) after its probe"
    }
} finally {
    if ($null -ne $process) { Stop-BoundedProcess $process }
    & $builder -ProfileRoot $profile -VerifyExternalSnapshot *>&1 | Add-Content -LiteralPath $log -Encoding utf8
    $runLogDirectory = Join-Path $runDirectory 'logs'
    if (Test-Path -LiteralPath $runLogDirectory) {
        Copy-Item -LiteralPath $runLogDirectory -Destination (Join-Path $evidenceDirectory "run-logs-$stamp") -Recurse
    }
}

if ($null -ne $process -and (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) {
    Write-R09Failure 'PROCESS_DID_NOT_EXIT' 'residual-process-check' `
            "Java process remains after bounded shutdown: pid=$($process.Id)" `
            -NextStep 'Stop the isolated Java process, retain logs, and inspect its shutdown path.'
}
$crashReports = @()
$crashDirectory = Join-Path $runDirectory 'crash-reports'
if (Test-Path -LiteralPath $crashDirectory) {
    $crashReports = @(Get-ChildItem -LiteralPath $crashDirectory -File)
}
if ($crashReports.Count -gt 0 -and $null -eq $failureCode) {
    $failureCode = 'FORGE_STARTUP_FAILED'
    $failureDetail = "Isolated server created $($crashReports.Count) crash report(s)"
}
if ($null -ne $failureCode) {
    Write-R09Failure $failureCode 'isolated-server-startup' $failureDetail `
            -NextStep 'Inspect the retained first-failure evidence; classify one explicit mod/dependency issue before changing the run copy.'
}
$runtimeKnowledgePath = Join-Path $runDirectory 'r09-evidence\runtime-knowledge.json'
if (-not (Test-Path -LiteralPath $runtimeKnowledgePath -PathType Leaf)) {
    Write-R09Failure 'RECIPE_MANAGER_UNAVAILABLE' 'runtime-knowledge-evidence' `
            'Server passed startup without writing the required RecipeManager evidence' `
            -NextStep 'Inspect the retained runtime probe log; do not substitute static KubeJS counts.'
}
$runtimeKnowledge = Get-Content -Raw -LiteralPath $runtimeKnowledgePath | ConvertFrom-Json
$typeTotal = [long](($runtimeKnowledge.recipeTypeCounts.PSObject.Properties | Measure-Object Value -Sum).Sum)
if ($runtimeKnowledge.schema -ne 'steve-industrial:r09-runtime-knowledge/v1' -or
        $runtimeKnowledge.evidenceSource -ne 'isolated-authoritative-RecipeManager' -or
        $runtimeKnowledge.staticScriptCountsUsedAsRuntimeTruth -ne $false -or
        $runtimeKnowledge.summary.recipeManagerTotal -lt 1 -or
        $typeTotal -ne $runtimeKnowledge.summary.recipeManagerTotal) {
    Write-R09Failure 'PACK_RECIPE_MAPPING_FAILED' 'runtime-knowledge-evidence' `
            'Structured RecipeManager evidence failed schema/source/count validation' `
            -Fingerprint $runtimeKnowledge.runtimeFingerprint `
            -NextStep 'Keep the report and correct only the read-only exporter; do not weaken mapping assertions.'
}
$mappedAndRejected = [long]$runtimeKnowledge.summary.mappedCrushingMillingPressing +
        [long]$runtimeKnowledge.summary.rejectedCrushingMillingPressing
$supportedTypeTotal = [long]$runtimeKnowledge.createRecipeTypeCounts.'create:crushing' +
        [long]$runtimeKnowledge.createRecipeTypeCounts.'create:milling' +
        [long]$runtimeKnowledge.createRecipeTypeCounts.'create:pressing'
if ($mappedAndRejected -ne $supportedTypeTotal) {
    Write-R09Failure 'PACK_RECIPE_MAPPING_FAILED' 'runtime-knowledge-evidence' `
            "Mapped/rejected total $mappedAndRejected does not account for crushing/milling/pressing total $supportedTypeTotal" `
            -Fingerprint $runtimeKnowledge.runtimeFingerprint `
            -NextStep 'Inspect every typed limitation; do not discard complex or probabilistic recipes.'
}
$c05 = $runtimeKnowledge.c05CrushingAcceptance
if ($null -eq $c05 -or $c05.status -ne 'PRESENT' -or
        $c05.recipeType -ne 'create:crushing' -or
        $c05.runtimeVerified -ne $true -or
        $c05.safeForPhaseIv -ne $true -or
        $c05.worldMutation -ne $false -or
        $c05.executionSessionCreated -ne $false -or
        @($c05.inputs).Count -ne 1 -or @($c05.outputs).Count -ne 1) {
    Write-R09Failure 'PACK_RECIPE_MAPPING_FAILED' 'c05-crushing-selection' `
            'DeceasedCraft runtime did not expose one bounded safe C-05 acceptance recipe' `
            -Fingerprint $runtimeKnowledge.runtimeFingerprint `
            -NextStep 'Retain NOT_PRESENT if no safe recipe exists; never fabricate a pack recipe.'
}
$mappedKubeJsMilling = @($runtimeKnowledge.mapping.mappedRecipes | Where-Object {
    $_.recipeId -like 'create:kjs/*' -and $_.recipeType -eq 'create:milling'
})
$mappedKubeJsPressing = @($runtimeKnowledge.mapping.mappedRecipes | Where-Object {
    $_.recipeId -like 'create:kjs/*' -and $_.recipeType -eq 'create:pressing'
})
if ($mappedKubeJsMilling.Count -lt 1 -or $mappedKubeJsPressing.Count -lt 1 -or
        @($runtimeKnowledge.sourceClues.kubejsCreateGeneratedRecipes).Count -lt 1 -or
        $runtimeKnowledge.summary.sequencedAssemblyRecipes -lt 1 -or
        $runtimeKnowledge.summary.sequencedPressingSteps -lt 1 -or
        $runtimeKnowledge.sequencedAssembly.supportStatus -ne 'typed-unsupported' -or
        $runtimeKnowledge.sequencedAssembly.unsupportedCode -ne 'RECIPE_TYPE_UNSUPPORTED') {
    Write-R09Failure 'PACK_RECIPE_MAPPING_FAILED' 'runtime-knowledge-evidence' `
            'KubeJS milling/pressing or typed sequenced-assembly boundary evidence is incomplete' `
            -Fingerprint $runtimeKnowledge.runtimeFingerprint `
            -NextStep 'Inspect runtime IDs and sequence structure; do not substitute static counts or flatten the sequence.'
}
$retainedKnowledge = Join-Path $evidenceDirectory "runtime-knowledge-$stamp.json"
Copy-Item -LiteralPath $runtimeKnowledgePath -Destination $retainedKnowledge
Copy-Item -LiteralPath $runtimeKnowledgePath -Destination (Join-Path $evidenceDirectory 'runtime-knowledge-latest.json') -Force
$knowledgeMarker = "R09_RUNTIME_KNOWLEDGE_VERIFIED total=$($runtimeKnowledge.summary.recipeManagerTotal) crushing=$($runtimeKnowledge.createRecipeTypeCounts.'create:crushing') milling=$($runtimeKnowledge.createRecipeTypeCounts.'create:milling') pressing=$($runtimeKnowledge.createRecipeTypeCounts.'create:pressing') sequencedAssembly=$($runtimeKnowledge.summary.sequencedAssemblyRecipes) sequencedPressingSteps=$($runtimeKnowledge.summary.sequencedPressingSteps) mapped=$($runtimeKnowledge.summary.mappedCrushingMillingPressing) rejected=$($runtimeKnowledge.summary.rejectedCrushingMillingPressing) warnings=$($runtimeKnowledge.summary.mappingWarnings) c05Status=$($c05.status) c05Recipe=$($c05.recipeId) c05Safe=$($c05.safeForPhaseIv) kubejsMillingMapped=$($mappedKubeJsMilling.Count) kubejsPressingMapped=$($mappedKubeJsPressing.Count) fingerprint=$($runtimeKnowledge.runtimeFingerprint) evidence=$retainedKnowledge"
$knowledgeMarker | Add-Content -LiteralPath $log -Encoding utf8
$runtimePlanningPath = Join-Path $runDirectory 'r09-evidence\runtime-planning.json'
if (-not (Test-Path -LiteralPath $runtimePlanningPath -PathType Leaf)) {
    Write-R09Failure 'RUNTIME_PROBE_TIMEOUT' 'runtime-planning-evidence' `
            'Server passed runtime knowledge export without writing planning evidence' `
            -Fingerprint $runtimeKnowledge.runtimeFingerprint `
            -NextStep 'Inspect the retained planning probe log; do not create a session or weaken verifier checks.'
}
$runtimePlanningRaw = Get-Content -Raw -LiteralPath $runtimePlanningPath
$runtimePlanning = $runtimePlanningRaw | ConvertFrom-Json
$successScenarios = @($runtimePlanning.scenarios | Where-Object status -eq 'SUCCESS')
$failureScenarios = @($runtimePlanning.scenarios | Where-Object status -eq 'FAILURE')
$customScenarios = @($successScenarios | Where-Object scenario -like 'custom-kubejs-*')
$missingScenario = @($failureScenarios | Where-Object scenario -eq 'missing-target')
$rejectedScenario = @($failureScenarios | Where-Object scenario -eq 'rejected-complex-or-probabilistic-recipe')
$disabledScenario = @($failureScenarios | Where-Object scenario -eq 'create-adapter-disabled')
$bindingScenarios = @($runtimePlanning.bindings | Where-Object status -eq 'SUCCESS')
$bindingFailures = @($runtimePlanning.bindingFailures | Where-Object status -eq 'FAILURE')
$physicalizations = @($runtimePlanning.physicalizations | Where-Object status -eq 'SUCCESS')
$physicalizationFailures = @($runtimePlanning.physicalizationFailures | Where-Object status -eq 'FAILURE')
$customBindings = @($bindingScenarios | Where-Object scenario -like 'custom-kubejs-*')
$millingBindings = @($bindingScenarios | Where-Object scenario -like '*milling*')
$pressingBindings = @($bindingScenarios | Where-Object scenario -like '*pressing*')
if ($runtimePlanning.schema -ne 'steve-industrial:r09-runtime-planning-binding-physicalization/v3' -or
        $runtimePlanning.runtimeFingerprint -ne $runtimeKnowledge.runtimeFingerprint -or
        $runtimePlanning.readOnly -ne $true -or
        $runtimePlanning.worldMutation -ne $false -or
        $runtimePlanning.executionSessionCreated -ne $false -or
        $runtimePlanning.implementationBinding -ne $true -or
        $runtimePlanning.physicalization -ne $true -or
        $runtimePlanning.physicalAuthority -ne $true -or
        $runtimePlanning.executionAuthority -ne $false -or
        $successScenarios.Count -ne 4 -or $failureScenarios.Count -ne 3 -or
        @($successScenarios | Where-Object {
            $_.outputType -ne 'VerifiedLogicalPlan' -or
            $_.verificationCheckCount -ne 8 -or
            @($_.verificationChecks).Count -ne 8 -or
            $_.worldMutation -ne $false -or $_.executionSessionCreated -ne $false
        }).Count -ne 0 -or
        $customScenarios.Count -ne 2 -or
        @($customScenarios | Where-Object { @($_.recipeIds | Where-Object { $_ -like 'create:kjs/*' }).Count -lt 1 }).Count -ne 0 -or
        $missingScenario.Count -ne 1 -or $missingScenario[0].code -ne 'RECIPE_NOT_FOUND' -or
        $rejectedScenario.Count -ne 1 -or $rejectedScenario[0].code -ne 'RECIPE_NOT_FOUND' -or
        $rejectedScenario[0].catalogRejectionCode -ne 'OUTPUT_UNSUPPORTED' -or
        $disabledScenario.Count -ne 1 -or $disabledScenario[0].code -ne 'CAPABILITY_CATALOG_MISSING' -or
        $runtimePlanning.bindingSuccessCount -ne 4 -or
        $runtimePlanning.bindingFailureCount -ne 2 -or
        $runtimePlanning.bindingVerifierChecks -ne 15 -or
        $runtimePlanning.bindingOutputType -ne 'VerifiedImplementationBoundPlan' -or
        $bindingScenarios.Count -ne 4 -or $bindingFailures.Count -ne 2 -or
        @($bindingScenarios | Where-Object {
            $_.outputType -ne 'VerifiedImplementationBoundPlan' -or
            $_.verificationCheckCount -ne 15 -or
            @($_.verificationChecks).Count -ne 15 -or
            $_.layoutAuthority -ne $false -or $_.executionAuthority -ne $false -or
            $_.worldMutation -ne $false -or $_.executionSessionCreated -ne $false
        }).Count -ne 0 -or
        @($millingBindings.nodes | Where-Object implementationId -ne 'create:mechanical_millstone').Count -ne 0 -or
        @($pressingBindings.nodes | Where-Object implementationId -ne 'create:mechanical_press').Count -ne 0 -or
        $customBindings.Count -ne 2 -or
        @($customBindings | Where-Object {
            @($_.nodes.recipeId | Where-Object { $_ -like 'create:kjs/*' }).Count -lt 1
        }).Count -ne 0 -or
        @($bindingFailures | Where-Object code -eq 'IMPLEMENTATION_RUNTIME_MISMATCH').Count -ne 1 -or
        @($bindingFailures | Where-Object code -eq 'IMPLEMENTATION_FORBIDDEN').Count -ne 1 -or
        $runtimePlanning.physicalizationSuccessCount -ne 12 -or
        $runtimePlanning.physicalizationFailureCount -ne 7 -or
        $runtimePlanning.physicalizationVerifierChecks -ne 13 -or
        $runtimePlanning.physicalizationOutputType -ne 'VerifiedPhysicalPlan' -or
        $runtimePlanning.sequencedAssemblyPhysicalizationRejected -ne $true -or
        $physicalizations.Count -ne 12 -or $physicalizationFailures.Count -ne 7 -or
        @($physicalizations | Where-Object {
            $_.outputType -ne 'VerifiedPhysicalPlan' -or
            $_.verificationCheckCount -ne 13 -or
            $_.readOnlySnapshot -ne $true -or $_.worldMutation -ne $false -or
            $_.executionSessionCreated -ne $false -or $_.executionAuthority -ne $false -or
            $_.unifiedNodes -lt 3 -or $_.unifiedEdges -lt 1
        }).Count -ne 0 -or
        @($physicalizations | Group-Object scenario | Where-Object Count -ne 3).Count -ne 0 -or
        @($physicalizations | Group-Object orientation | Where-Object Count -ne 4).Count -ne 0 -or
        @($physicalizationFailures | Where-Object code -eq 'WORLD_SNAPSHOT_STALE').Count -ne 1 -or
        @($physicalizationFailures | Where-Object code -eq 'PLACEMENT_COLLISION').Count -ne 1 -or
        @($physicalizationFailures | Where-Object code -eq 'CLEARANCE_BLOCKED').Count -ne 1 -or
        @($physicalizationFailures | Where-Object code -eq 'ROUTE_CAPACITY_INSUFFICIENT').Count -ne 1 -or
        @($physicalizationFailures | Where-Object code -eq 'ROTATIONAL_POWER_ROUTE_NOT_FOUND').Count -ne 1 -or
        @($physicalizationFailures | Where-Object code -eq 'STRESS_CAPACITY_INSUFFICIENT').Count -ne 1 -or
        @($physicalizationFailures | Where-Object code -eq 'ITEM_ROUTE_NOT_FOUND').Count -ne 1 -or
        $runtimePlanning.repeatabilityChecks -ne 1 -or
        $runtimePlanningRaw -match '"(genericExecutionSession|boundedStepRunner|executionJournal)"') {
    Write-R09Failure 'PACK_RECIPE_MAPPING_FAILED' 'runtime-planning-evidence' `
            'Pack-backed planning evidence failed scenario, verifier, typed-failure, authority, or determinism validation' `
            -Fingerprint $runtimeKnowledge.runtimeFingerprint `
            -NextStep 'Retain the report and correct only the read-only planning probe; do not add execution or physical binding.'
}
$retainedPlanning = Join-Path $evidenceDirectory "runtime-planning-$stamp.json"
Copy-Item -LiteralPath $runtimePlanningPath -Destination $retainedPlanning
Copy-Item -LiteralPath $runtimePlanningPath -Destination (Join-Path $evidenceDirectory 'runtime-planning-latest.json') -Force
$planningMarker = "R09_RUNTIME_PLANNING_VERIFIED successes=$($successScenarios.Count) failures=$($failureScenarios.Count) verifierChecks=8 customScenarios=$($customScenarios.Count) bindingSuccesses=$($bindingScenarios.Count) bindingFailures=$($bindingFailures.Count) bindingVerifierChecks=15 physicalizationSuccesses=$($physicalizations.Count) physicalizationFailures=$($physicalizationFailures.Count) physicalizationVerifierChecks=13 orientations=3 sequencedAssemblyRejected=$($runtimePlanning.sequencedAssemblyPhysicalizationRejected) millstoneBindings=$($millingBindings.Count) pressBindings=$($pressingBindings.Count) missingCode=$($missingScenario[0].code) rejectedRecipe=$($rejectedScenario[0].rejectedRecipeId) rejectedCode=$($rejectedScenario[0].catalogRejectionCode) disabledAdapterCode=$($disabledScenario[0].code) repeatabilityChecks=$($runtimePlanning.repeatabilityChecks) outputType=VerifiedLogicalPlan bindingOutputType=VerifiedImplementationBoundPlan physicalizationOutputType=VerifiedPhysicalPlan executionAuthority=false worldMutation=false sessionCreated=false fingerprint=$($runtimePlanning.runtimeFingerprint) evidence=$retainedPlanning"
$planningMarker | Add-Content -LiteralPath $log -Encoding utf8
$pilotGoalCount = 0
if ($ExecutionPilot) {
    $pilotEvidencePath = Join-Path $runDirectory 'r09-evidence\execution-pilot.json'
    if (-not (Test-Path -LiteralPath $pilotEvidencePath -PathType Leaf)) {
        Write-R09Failure 'PROCESS_TIMEOUT' 'execution-pilot-evidence' `
                'Execution pilot passed no structured evidence file' `
                -NextStep 'Inspect the isolated pilot log and retain the first typed failure.'
    }
    $pilot = Get-Content -Raw -LiteralPath $pilotEvidencePath | ConvertFrom-Json
    $pilotScenarios = @($pilot.scenarios)
    $cokePilot = @($pilotScenarios | Where-Object target -eq 'immersiveengineering:dust_coke')
    $canPilot = @($pilotScenarios | Where-Object target -eq 'apocalypsenow:can')
    if ($pilot.schema -ne 'steve-industrial:r09-execution-pilot/v1' -or
            $pilot.status -ne 'PASS' -or $pilot.executionWorld -ne 'isolated-repository-test' -or
            $pilot.formalWorld -ne $false -or $pilot.externalMutation -ne $false -or
            $pilot.savesOrFormalWorldRead -ne $false -or $pilot.goalCount -ne 2 -or
            $pilotScenarios.Count -ne 2 -or $cokePilot.Count -ne 1 -or $canPilot.Count -ne 1 -or
            $cokePilot[0].requiredQuantity -ne 4 -or $cokePilot[0].observedQuantity -lt 4 -or
            $cokePilot[0].rawInput -ne 'immersiveengineering:coal_coke' -or
            @($cokePilot[0].recipeIds | Where-Object { $_ -like 'create:kjs/*' }).Count -lt 1 -or
            $canPilot[0].requiredQuantity -ne 3 -or $canPilot[0].observedQuantity -lt 3 -or
            $canPilot[0].rawInput -ne 'immersiveengineering:ingot_aluminum' -or
            @($canPilot[0].recipeIds | Where-Object { $_ -like 'create:kjs/*' }).Count -lt 1 -or
            @($canPilot[0].ingredientSelections | Where-Object {
                $_.ingredientIdentity -like 'tag:forge:plates/aluminum=*'
            }).Count -lt 1 -or
            @($pilotScenarios | Where-Object {
                $_.planningChecks -ne 8 -or $_.bindingChecks -ne 15 -or
                $_.physicalizationChecks -ne 13 -or $_.readinessChecks -ne 16 -or
                $_.maximumHandlerInvocationsPerTick -ne 1 -or $_.cleanupVerified -ne $true -or
                $_.journals -lt 1 -or $_.journalEntries -lt 1
            }).Count -ne 0) {
        Write-R09Failure 'PACK_RECIPE_MAPPING_FAILED' 'execution-pilot-evidence' `
                'Execution pilot evidence failed recipe, Ingredient, quantity, verifier, journal, bound, or cleanup validation' `
                -Fingerprint $runtimePlanning.runtimeFingerprint `
                -NextStep 'Retain the typed evidence and correct the isolated production path only.'
    }
    $pilotGoalCount = $pilotScenarios.Count
    $retainedPilot = Join-Path $evidenceDirectory "execution-pilot-$stamp.json"
    Copy-Item -LiteralPath $pilotEvidencePath -Destination $retainedPilot
    Copy-Item -LiteralPath $pilotEvidencePath -Destination (Join-Path $evidenceDirectory 'execution-pilot-latest.json') -Force
    "R09_EXECUTION_PILOT_VERIFIED goals=$pilotGoalCount cokeQuantity=$($cokePilot[0].observedQuantity) canQuantity=$($canPilot[0].observedQuantity) tagIdentityPreserved=true planningChecks=8 bindingChecks=15 physicalChecks=13 readinessChecks=16 maxHandlerInvocationsPerTick=1 cleanupVerified=true externalMutation=false savesOrFormalWorldRead=false evidence=$retainedPilot" |
        Add-Content -LiteralPath $log -Encoding utf8
}
$fatalPatterns = @(
    '\[[^\]]+/FATAL\]'
    'ModLoadingException'
    'Preparing crash report'
    'R09_PACK_STARTUP_FAIL'
    'R09_EXECUTION_PILOT_FAIL'
    '\[Server thread/ERROR\].*(Exception|failed|crash)'
)
$fatalMatches = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatalMatches.Count -gt 0) {
    Write-R09Failure 'FORGE_STARTUP_FAILED' 'strict-log-scan' `
            "Accepted startup log contains $($fatalMatches.Count) fatal marker(s)" `
            -NextStep 'Inspect each retained marker and make only a minimal evidence-backed isolated-profile change.'
}
$nonFatalPatterns = @(
    '\[[^\]]+/ERROR\].*RuntimeDistCleaner/DISTXFORM'
    'ClassNotFoundException'
    'Exception in thread "Thread-1"'
)
$nonFatalDiagnostics = @(Select-String -LiteralPath $log -Pattern $nonFatalPatterns -CaseSensitive:$false)
$diagnosticMarker = "R09_PACK_STARTUP_DIAGNOSTICS nonFatalMarkers=$($nonFatalDiagnostics.Count) filteredMods=0 action=retain-and-document"
$diagnosticMarker | Add-Content -LiteralPath $log -Encoding utf8
$marker = Select-String -LiteralPath $log -Pattern 'R09_PACK_STARTUP_PASS' | Select-Object -Last 1
$deploymentEvidencePath = Join-Path $runDirectory 'r09-evidence\deployment-dry-run.txt'
if (-not (Test-Path -LiteralPath $deploymentEvidencePath -PathType Leaf)) {
    Write-R09Failure 'ISOLATED_WORLD_FAILED' 'deployment-dry-run' `
            "PW-12 DeceasedCraft command evidence file is missing: $deploymentEvidencePath" `
            -NextStep 'Inspect only the isolated command output and typed failure context.'
}
$deploymentEvidence = @(Get-Content -LiteralPath $deploymentEvidencePath)
if ($deploymentEvidence.Count -ne 13 -or
        $deploymentEvidence[0] -notmatch 'schema=steve-industrial:pw12-deployment-dry-run/v1 commands=12 targets=2 orientations=3.*dryRun=true formalWorldExecutable=false worldMutation=false sessionCreated=false playerItemsConsumed=false machineStarted=false llmCalled=false freeTextCoordinatesAccepted=false') {
    Write-R09Failure 'ISOLATED_WORLD_FAILED' 'deployment-dry-run' `
            'PW-12 DeceasedCraft command evidence schema or safety summary is invalid' `
            -NextStep 'Inspect the isolated evidence file without weakening any zero-side-effect assertion.'
}
$deploymentCommandLines = @($deploymentEvidence | Select-Object -Skip 1)
if (@($deploymentCommandLines | Where-Object { $_ -notmatch '^command=/industrialagent deploy (preview|risks|budget|readiness) .+ result=1$' }).Count -ne 0 -or
        @($deploymentCommandLines | Where-Object { $_ -match 'immersiveengineering:dust_coke 4' }).Count -ne 6 -or
        @($deploymentCommandLines | Where-Object { $_ -match 'apocalypsenow:can 3' }).Count -ne 6 -or
        @($deploymentCommandLines | Where-Object { $_ -match 'preview .+ (zero|clockwise_90|clockwise_270) result=1$' }).Count -ne 6) {
    Write-R09Failure 'ISOLATED_WORLD_FAILED' 'deployment-dry-run' `
            'PW-12 command evidence does not contain both custom targets and the complete orientation/view matrix' `
            -NextStep 'Inspect the exact 12 command/result lines in the disposable evidence file.'
}
$loadedMods = if ($marker.Line -match 'loadedMods=(\d+)') { [int]$Matches[1] } else { 0 }
$elapsedSeconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
$verified = "R09_ISOLATED_SERVER_VERIFIED gameDir=$runDirectory jar=$($productionJar.Name) sha256=$productionHash loadedMods=$loadedMods filteredClientMods=0 recipeManagerTotal=$($runtimeKnowledge.summary.recipeManagerTotal) milling=$($runtimeKnowledge.createRecipeTypeCounts.'create:milling') pressing=$($runtimeKnowledge.createRecipeTypeCounts.'create:pressing') mapped=$($runtimeKnowledge.summary.mappedMillingPressing) rejected=$($runtimeKnowledge.summary.rejectedMillingPressing) planningSuccesses=$($successScenarios.Count) planningFailures=$($failureScenarios.Count) verifierChecks=8 bindingSuccesses=$($bindingScenarios.Count) bindingFailures=$($bindingFailures.Count) bindingVerifierChecks=15 physicalizationSuccesses=$($physicalizations.Count) physicalizationFailures=$($physicalizationFailures.Count) physicalizationVerifierChecks=13 physicalOrientations=3 sequencedAssemblyRejected=true deploymentDryRunCommands=12 deploymentDryRunTargets=2 deploymentDryRunOrientations=3 deploymentFormalExecutable=false deploymentWorldMutation=false deploymentSessionCreated=false executionPilot=$($ExecutionPilot.IsPresent) executionPilotGoals=$pilotGoalCount nonFatalDiagnostics=$($nonFatalDiagnostics.Count) startupBound=$StartupTimeoutSeconds initializationBound=$InitializationTimeoutSeconds planningBound=$PlanningTimeoutSeconds shutdownBound=$ShutdownTimeoutSeconds elapsedSeconds=$elapsedSeconds cleanExit=true residualProcesses=0 crashReports=0 externalMutation=false savesOrFormalWorldRead=false"
$verified | Add-Content -LiteralPath $log -Encoding utf8
Write-Output $verified
Write-Output "Test log: $log"
