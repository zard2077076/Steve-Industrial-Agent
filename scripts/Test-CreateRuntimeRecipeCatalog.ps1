$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $runRoot 'create-runtime-recipe-catalog-acceptance'))
$logDirectory = Join-Path $root 'work\logs'

$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$runPrefix = $runRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($runPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    -not $runDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    $runDirectory -eq $runRoot) {
    throw "Refusing to use an unsafe runtime recipe acceptance directory: $runDirectory"
}

if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory | Out-Null
@(
    '# Generated for the isolated Steve Industrial Agent runtime-catalog acceptance.'
    'eula=true'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
@(
    '# Generated for an isolated, non-public read-only runtime-catalog acceptance server.'
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'level-seed=steve-industrial-r01-v1'
    'generate-structures=false'
    'view-distance=3'
    'simulation-distance=3'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ascii
'steve-industrial:deployment-dry-run/v1' | Set-Content -LiteralPath `
    (Join-Path $runDirectory '.steve-industrial-deployment-dry-run') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory ('create-runtime-recipe-catalog-' + $started.ToString('yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') `
    -PcreateRuntimeRecipeCatalogAcceptance `
    :forge-create-1.20.1:runServer *>&1 | Tee-Object -FilePath $log

function Assert-LogContains([string]$Expected, [string]$Description) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $log. Expected: $Expected"
    }
}

Assert-LogContains 'java version 17.' 'Java 17 launch evidence'
Assert-LogContains 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge/Minecraft version evidence'
Assert-LogContains 'Create 6.0.6 initializing!' 'Create 6.0.6 initialization evidence'
Assert-LogContains 'CREATE_RUNTIME_RECIPE_BOUNDARY PASS minecraft=1.20.1 forge=47.4.10 create=6.0.6-150' 'runtime catalog PASS marker'
Assert-LogContains 'gravelCrushing=true cobblestoneMilling=true ironPressing=true' 'real crushing, milling and pressing mapping evidence'
Assert-LogContains 'capabilities=3 crushingCapability=true millingCapability=true pressingCapability=true implementations=3 crusherImplementation=true millstoneImplementation=true pressImplementation=true implementationFingerprintReloaded=true' 'runtime capability and implementation evidence'
Assert-LogContains 'boundPlans=2 gravelBoundNodes=2 gravelImplementation=create:mechanical_millstone ironSheetBoundNodes=1 ironSheetImplementation=create:mechanical_press bindingIngredientIdentity=true bindingLayoutAuthority=false physicalPlans=6 physicalOrientations=3 physicalVerifierChecks=13 physicalOutputType=VerifiedPhysicalPlan physicalDeterministic=true physicalFailures=3 sequencedAssemblyPhysicalizationRejected=true physicalExecutionAuthority=false' 'verified runtime implementation binding and physicalization evidence'
Assert-LogContains 'CREATE_RUNTIME_BIND_RESULT runtimeBinding={status=PASS' 'read-only binding command structured success evidence'
Assert-LogContains 'CREATE_RUNTIME_BIND_RESULT runtimeBinding={status=FAIL,code=IMPLEMENTATION_NOT_FOUND,stage=COMMAND' 'read-only binding command structured failure evidence'
Assert-LogContains 'gravelPlanRecipe=create:milling/cobblestone gravelQuantity=3 gravelExecutions=3 gravelSteps=2 gravelRaw=minecraft:andesite' 'real runtime multi-step milling planning evidence'
Assert-LogContains 'ironSheetPlanRecipe=create:pressing/iron_ingot ironSheetQuantity=2 ironSheetExecutions=2' 'real runtime pressing planning evidence'
Assert-LogContains 'deterministicPlans=true verifiedLogicalPlans=true' 'deterministic verified logical planning evidence'
Assert-LogContains 'CREATE_RUNTIME_PLAN_RESULT runtimePlanning={status=PASS,target=minecraft:gravel,quantity=3' 'read-only command structured success evidence'
Assert-LogContains 'CREATE_RUNTIME_PLAN_RESULT runtimePlanning={status=FAIL,code=RECIPE_NOT_FOUND,stage=PLANNING,target=minecraft:diamond' 'read-only command structured typed failure evidence'
Assert-LogContains 'commandSuccess=true commandTypedFailure=true commandValidationRejected=true commandWrongThreadRejected=true commandWorldMutation=false' 'read-only command boundary evidence'
Assert-LogContains 'deploymentCommands=10 deploymentOrientations=3 deploymentTargets=2 deploymentPreview=true deploymentRisks=true deploymentBudget=true deploymentReadiness=true deploymentValidationRejected=true deploymentWrongThreadRejected=true deploymentFormalExecutable=false deploymentWorldMutation=false deploymentSessionCreated=false deploymentPlayerItemsConsumed=false deploymentMachineStarted=false deploymentLlmCalled=false deploymentFreeTextCoordinates=false' 'PW-12 read-only deployment command evidence'
Assert-LogContains 'DEPLOYMENT_DRY_RUN_RESULT deploymentDryRun={status=PASS,section=PREVIEW,target=minecraft:gravel,quantity=3' 'PW-12 gravel preview command'
Assert-LogContains 'DEPLOYMENT_DRY_RUN_RESULT deploymentDryRun={status=PASS,section=PREVIEW,target=create:iron_sheet,quantity=2' 'PW-12 iron-sheet preview command'
Assert-LogContains 'missingAuthorization=[REGION_AUTHORIZATION, CLAIM_PERMISSION, HUMAN_APPROVAL],missingBackup=[BACKUP_PLAN, BACKUP_MANIFEST, RESTORE_VERIFICATION],readiness=BLOCKED,dryRun=true,formalWorldExecutable=false' 'PW-12 missing gate and formal refusal evidence'
Assert-LogContains 'noWorldMutation=true sessionCreated=false' 'no world mutation or execution session evidence'
Assert-LogContains 'generationBefore=0 generationAfter=1 fingerprintChanged=true noWorldMutation=true' 'reload and read-only evidence'
Assert-LogContains 'STANDARD_RUNTIME_KNOWLEDGE_EXPORT PASS' 'structured standard runtime knowledge export'
Assert-LogContains 'CREATE_CAPABILITY_TASK_GRAPH_CONTRACT PASS' 'C-05 through C-10 frozen executor-contract task graph evidence'
Assert-LogContains 'directConstrained=true botsUnsupported=true hybridConstrained=true verifiedPhysicalPlanBound=true runtimeBound=true worldSnapshotBound=true freeCoordinates=false executorCreated=false' 'three-mode capability descriptor and verified-plan binding evidence'
Assert-LogContains 'CREATE_CAPABILITY_RUNTIME_OBSERVERS PASS' 'v606 read-only capability observer evidence'
Assert-LogContains 'wrongThreadRejected=true assignmentBound=true verifiedPhysicalPlanBound=true runtimeBound=true worldSnapshotBound=true inputDeltaRequired=true outputDeltaRequired=true fixedSleep=false worldMutation=false' 'observer assignment, snapshot and before/after evidence boundary'
Assert-LogContains 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean server shutdown evidence'

$standardKnowledgePath = Join-Path $runDirectory 'r09-evidence\runtime-knowledge.json'
if (-not (Test-Path -LiteralPath $standardKnowledgePath -PathType Leaf)) {
    throw "Standard runtime knowledge JSON is missing: $standardKnowledgePath"
}
$standardKnowledge = Get-Content -Raw -LiteralPath $standardKnowledgePath | ConvertFrom-Json
$standardTypeTotal = [long](($standardKnowledge.recipeTypeCounts.PSObject.Properties |
    Measure-Object Value -Sum).Sum)
if ($standardKnowledge.schema -ne 'steve-industrial:r09-runtime-knowledge/v1' -or
        $standardKnowledge.evidenceSource -ne 'isolated-authoritative-RecipeManager' -or
        $standardTypeTotal -ne $standardKnowledge.summary.recipeManagerTotal) {
    throw 'Standard runtime knowledge JSON failed schema/source/type-count validation'
}
if ($standardKnowledge.c05CrushingAcceptance.status -ne 'NOT_PRESENT') {
    throw 'Standard Create profile incorrectly claimed a DeceasedCraft-namespace C-05 recipe'
}

$capabilityExpansion = $standardKnowledge.capabilityExpansion
$expectedCapabilities = @(
    'CRUSHING'
    'FAN_WASHING'
    'FAN_SMOKING'
    'FAN_HAUNTING'
    'FAN_BLASTING'
    'CUTTING'
    'MIXING_PHASE_I'
    'COMPACTING_PHASE_I'
    'DEPLOYING_PHASE_I'
)
if ($null -eq $capabilityExpansion -or
        $capabilityExpansion.schema -ne 'steve-industrial:create-capability-census/v1' -or
        $capabilityExpansion.serverAuthoritative -ne $true -or
        $capabilityExpansion.worldMutation -ne $false -or
        $capabilityExpansion.executorCreated -ne $false) {
    throw 'C-05 through C-10 capability census failed schema/authority/safety validation'
}

$targetTypeTotal = [long](($capabilityExpansion.targetRecipeTypeCounts.PSObject.Properties |
    Measure-Object Value -Sum).Sum)
if ($targetTypeTotal -ne [long]$capabilityExpansion.targetRecipeTotal -or
        @($capabilityExpansion.recipes).Count -ne [int]$capabilityExpansion.targetRecipeTotal) {
    throw "Capability census did not account for every target recipe: typeTotal=$targetTypeTotal targetTotal=$($capabilityExpansion.targetRecipeTotal) recipes=$(@($capabilityExpansion.recipes).Count)"
}

foreach ($capabilityName in $expectedCapabilities) {
    $property = $capabilityExpansion.supportMatrix.PSObject.Properties[$capabilityName]
    if ($null -eq $property) {
        throw "Capability census is missing support-matrix row $capabilityName"
    }
    $row = $property.Value
    $accounted = [long]$row.supportedPhaseI + [long]$row.semanticsOnly + [long]$row.unsupported
    if ([long]$row.discovered -ne $accounted -or [long]$row.discovered -le 0 -or $row.notPresent -ne $false) {
        throw "Capability row $capabilityName is absent or unaccounted: discovered=$($row.discovered) accounted=$accounted notPresent=$($row.notPresent)"
    }
    if (@($row.acceptanceCandidates).Count -le 0) {
        throw "Capability row $capabilityName has no Phase-I acceptance candidate"
    }
}

foreach ($recipe in @($capabilityExpansion.recipes)) {
    if ($recipe.requirements.itemProcessingOnly -ne $true -or
            $recipe.requirements.arbitraryWorldInteractionForbidden -ne $true -or
            $recipe.requirements.playerInventoryForbidden -ne $true -or
            $recipe.requirements.runtimeObservation.fixedSleepForbidden -ne $true) {
        throw "Capability recipe $($recipe.recipeId) violated the item-only/readback/no-fixed-sleep contract"
    }
    if (@($recipe.itemOutputs).Count -gt 0 -and
            $recipe.itemOutputs[0].primary -ne $true) {
        throw "Capability recipe $($recipe.recipeId) lost deterministic primary-output ordering"
    }
}

$marker = Select-String -LiteralPath $log -Pattern 'CREATE_RUNTIME_RECIPE_BOUNDARY PASS' | Select-Object -Last 1
if ($null -eq $marker -or $marker.Line -notmatch 'discovered=(\d+)') {
    throw "Runtime catalog PASS marker did not expose an actual RecipeManager count: $($marker.Line)"
}
$discovered = [int]$matches[1]
if ($discovered -le 0) {
    throw "Runtime RecipeManager count must be positive, found $discovered"
}
if ($marker.Line -notmatch 'supported=(\d+) mapped=(\d+) rejected=(\d+) crushingDiscovered=(\d+) millingDiscovered=(\d+) pressingDiscovered=(\d+) crushingMapped=(\d+) millingMapped=(\d+) pressingMapped=(\d+) warnings=(\d+)') {
    throw "Runtime catalog PASS marker did not expose mapping statistics: $($marker.Line)"
}
$supported = [int]$matches[1]
$mapped = [int]$matches[2]
$rejected = [int]$matches[3]
$crushingDiscovered = [int]$matches[4]
$millingDiscovered = [int]$matches[5]
$pressingDiscovered = [int]$matches[6]
$crushingMapped = [int]$matches[7]
$millingMapped = [int]$matches[8]
$pressingMapped = [int]$matches[9]
$warnings = [int]$matches[10]
if ($supported -ne ($mapped + $rejected) -or
    $mapped -le 2 -or
    $crushingMapped -le 1 -or $millingMapped -le 1 -or
    $pressingMapped -le 1) {
    throw "Runtime catalog looks fixed or incomplete: supported=$supported mapped=$mapped rejected=$rejected crushingMapped=$crushingMapped millingMapped=$millingMapped pressingMapped=$pressingMapped"
}

$crashDirectory = Join-Path $runDirectory 'crash-reports'
$newCrashReports = @()
if (Test-Path -LiteralPath $crashDirectory) {
    $newCrashReports = @(Get-ChildItem -LiteralPath $crashDirectory -File |
        Where-Object LastWriteTime -ge $started)
}
if ($newCrashReports.Count -gt 0) {
    throw "Runtime catalog acceptance created crash report(s): $($newCrashReports.FullName -join ', ')"
}

$fatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]',
    'Exception in thread',
    'CREATE_RUNTIME_RECIPE_BOUNDARY FAIL',
    'Preparing crash report'
)
$fatalMatches = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatalMatches.Count -gt 0) {
    throw "Runtime catalog acceptance log contains fatal/error evidence: $($fatalMatches.Line -join ' | ')"
}

$authorityFiles = @(
    (Join-Path $root 'adapter-api\src\main\java\dev\stevecreate\agent\adapter\api\RuntimeKnowledgePlanningService.java')
    (Join-Path $root 'adapter-api\src\main\java\dev\stevecreate\agent\adapter\api\RuntimeVerifiedPlanningResult.java')
    (Join-Path $root 'adapter-api\src\main\java\dev\stevecreate\agent\adapter\api\RuntimePlanningCommandFormatter.java')
    (Join-Path $root 'adapter-api\src\main\java\dev\stevecreate\agent\adapter\api\RuntimeImplementationBindingService.java')
    (Join-Path $root 'adapter-api\src\main\java\dev\stevecreate\agent\adapter\api\RuntimeBindingCommandFormatter.java')
    (Join-Path $root 'forge-create-1.20.1\src\main\java\dev\stevecreate\agent\forge1201\command\CreateRuntimePlanningCommand.java')
    (Join-Path $root 'forge-create-1.20.1\src\main\java\dev\stevecreate\agent\forge1201\command\CreateRuntimeBindingCommand.java')
)
$authorityPatterns = @(
    'GenericExecutionSession'
    'BoundedStepRunner'
    'UnifiedMachineGraph'
    'BlockPos3i'
    'MachineOrientation'
    'Create606WaterWheelMillstoneExecutor'
    'Create606BeltPressExecutor'
)
$authorityMatches = @(Select-String -LiteralPath $authorityFiles -Pattern $authorityPatterns -CaseSensitive)
if ($authorityMatches.Count -gt 0) {
    throw "Runtime planning command gained forbidden physical/execution authority: $($authorityMatches.Line -join ' | ')"
}

Write-Output "CREATE_RUNTIME_RECIPE_BOUNDARY_VERIFIED discovered=$discovered supported=$supported mapped=$mapped rejected=$rejected crushingDiscovered=$crushingDiscovered millingDiscovered=$millingDiscovered pressingDiscovered=$pressingDiscovered crushingMapped=$crushingMapped millingMapped=$millingMapped pressingMapped=$pressingMapped warnings=$warnings capabilities=3 implementations=3 implementationFingerprintReloaded=true physicalRecipeProofs=3 runtimePlans=2 deterministicPlans=true verifiedLogicalPlans=true physicalPlans=6 physicalOrientations=3 physicalVerifierChecks=13 physicalFailures=3 physicalDeterministic=true sequencedAssemblyPhysicalizationRejected=true physicalExecutionAuthority=false commandSuccess=true commandTypedFailure=true commandValidationRejected=true commandWrongThreadRejected=true commandWorldMutation=false generationInvalidated=true fingerprintChanged=true noWorldMutation=true sessionCreated=false authorityScan=true crashReports=0"
Write-Output "Standard runtime knowledge: $standardKnowledgePath"
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
