param(
    [string]$StandardKnowledgePath,
    [string]$PackKnowledgePath,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$profileEvidence = Join-Path $repoRoot 'work\isolated-pack\deceasedcraft-r09\evidence'
if ([string]::IsNullOrWhiteSpace($StandardKnowledgePath)) {
    $StandardKnowledgePath = Join-Path $repoRoot 'forge-create-1.20.1\run\create-runtime-recipe-catalog-acceptance\r09-evidence\runtime-knowledge.json'
}
if ([string]::IsNullOrWhiteSpace($PackKnowledgePath)) {
    $PackKnowledgePath = Join-Path $profileEvidence 'runtime-knowledge-latest.json'
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $profileEvidence 'runtime-difference.json'
}

foreach ($path in @($StandardKnowledgePath, $PackKnowledgePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Runtime knowledge input is missing: $path"
    }
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputPath) | Out-Null
$standard = Get-Content -Raw -LiteralPath $StandardKnowledgePath | ConvertFrom-Json
$pack = Get-Content -Raw -LiteralPath $PackKnowledgePath | ConvertFrom-Json

function Assert-Knowledge([object]$Value, [string]$Name) {
    $typeTotal = [long](($Value.recipeTypeCounts.PSObject.Properties | Measure-Object Value -Sum).Sum)
    if ($Value.schema -ne 'steve-industrial:r09-runtime-knowledge/v1' -or
            $Value.evidenceSource -ne 'isolated-authoritative-RecipeManager' -or
            $Value.staticScriptCountsUsedAsRuntimeTruth -ne $false -or
            $typeTotal -ne $Value.summary.recipeManagerTotal) {
        throw "$Name runtime knowledge failed source/schema/count validation"
    }
}
Assert-Knowledge $standard 'standard'
Assert-Knowledge $pack 'pack'

function Get-IngredientSignature([object[]]$Inputs) {
    return @($Inputs | ForEach-Object {
        $identity = [string]$_.canonicalIdentity
        $identity = $identity -replace '#sha256:[0-9a-f]+$', ''
        "$($_.kind)|$identity|candidates=$((@($_.runtimeCandidates) -join ','))"
    } | Sort-Object) -join ';'
}

function Get-ResourceSignature([object[]]$Resources) {
    return @($Resources | ForEach-Object {
        "$($_.resourceType):$($_.resourceId)@$($_.amount)"
    } | Sort-Object) -join ';'
}

function Get-RecipeRows([object]$Knowledge) {
    $rows = @{}
    foreach ($recipe in @($Knowledge.mapping.mappedRecipes)) {
        $rows[$recipe.recipeId] = [pscustomobject][ordered]@{
            recipeId = [string]$recipe.recipeId
            status = 'mapped'
            recipeType = [string]$recipe.recipeType
            ingredientSignature = Get-IngredientSignature @($recipe.inputs)
            outputSignature = Get-ResourceSignature @($recipe.outputs)
            byproductSignature = Get-ResourceSignature @($recipe.optionalByproducts)
            processingTicks = [string]$recipe.processingTicks
            limitationCode = ''
            limitationDetail = ''
        }
    }
    foreach ($limitation in @($Knowledge.mapping.limitations)) {
        $typeTrace = @($limitation.trace | Where-Object { $_ -like 'recipe_type:*' } | Select-Object -First 1)
        $type = if ($typeTrace.Count -gt 0) { $typeTrace[0].Substring('recipe_type:'.Length) } else { '' }
        $rows[$limitation.recipeId] = [pscustomobject][ordered]@{
            recipeId = [string]$limitation.recipeId
            status = 'rejected'
            recipeType = $type
            ingredientSignature = [string]$limitation.ingredientIdentity
            outputSignature = ''
            byproductSignature = ''
            processingTicks = ''
            limitationCode = [string]$limitation.code
            limitationDetail = [string]$limitation.detail
        }
    }
    return $rows
}

$standardRows = Get-RecipeRows $standard
$packRows = Get-RecipeRows $pack
$standardIds = @($standardRows.Keys | Sort-Object)
$packIds = @($packRows.Keys | Sort-Object)
$addedIds = @($packIds | Where-Object { -not $standardRows.ContainsKey($_) })
$removedIds = @($standardIds | Where-Object { -not $packRows.ContainsKey($_) })
$commonIds = @($packIds | Where-Object { $standardRows.ContainsKey($_) })
$modified = [Collections.Generic.List[object]]::new()
$unchanged = [Collections.Generic.List[string]]::new()
foreach ($id in $commonIds) {
    $left = $standardRows[$id]
    $right = $packRows[$id]
    $typeChanged = $left.recipeType -ne $right.recipeType
    $ingredientChanged = $left.ingredientSignature -ne $right.ingredientSignature
    $outputChanged = $left.outputSignature -ne $right.outputSignature -or
            $left.byproductSignature -ne $right.byproductSignature
    $mappingStatusChanged = $left.status -ne $right.status -or
            $left.limitationCode -ne $right.limitationCode
    $durationChanged = $left.processingTicks -ne $right.processingTicks
    if ($typeChanged -or $ingredientChanged -or $outputChanged -or
            $mappingStatusChanged -or $durationChanged) {
        $modified.Add([pscustomobject][ordered]@{
            recipeId = $id
            typeChanged = $typeChanged
            ingredientChanged = $ingredientChanged
            outputChanged = $outputChanged
            mappingStatusChanged = $mappingStatusChanged
            durationChanged = $durationChanged
            standard = $left
            pack = $right
        })
    } else {
        $unchanged.Add($id)
    }
}

$added = @($addedIds | ForEach-Object { $packRows[$_] })
$removed = @($removedIds | ForEach-Object { $standardRows[$_] })
$customKubeJs = @($pack.mapping.mappedRecipes | Where-Object recipeId -like 'create:kjs/*' |
    Sort-Object recipeId)
$report = [ordered]@{
    schema = 'steve-industrial:r09-runtime-difference/v1'
    comparisonBasis = 'authoritative-runtime-knowledge-json'
    staticScriptCountsUsedAsRuntimeTruth = $false
    standard = [ordered]@{
        runtimeFingerprint = $standard.runtimeFingerprint
        recipeManagerTotal = $standard.summary.recipeManagerTotal
        milling = $standard.createRecipeTypeCounts.'create:milling'
        pressing = $standard.createRecipeTypeCounts.'create:pressing'
        mapped = $standard.summary.mappedMillingPressing
        rejected = $standard.summary.rejectedMillingPressing
    }
    pack = [ordered]@{
        runtimeFingerprint = $pack.runtimeFingerprint
        recipeManagerTotal = $pack.summary.recipeManagerTotal
        milling = $pack.createRecipeTypeCounts.'create:milling'
        pressing = $pack.createRecipeTypeCounts.'create:pressing'
        mapped = $pack.summary.mappedMillingPressing
        rejected = $pack.summary.rejectedMillingPressing
    }
    summary = [ordered]@{
        standardComparedRecipes = $standardRows.Count
        packComparedRecipes = $packRows.Count
        added = $added.Count
        removed = $removed.Count
        modified = $modified.Count
        unchanged = $unchanged.Count
        ingredientChanged = @($modified | Where-Object ingredientChanged).Count
        outputChanged = @($modified | Where-Object outputChanged).Count
        typeChanged = @($modified | Where-Object typeChanged).Count
        mappingStatusChanged = @($modified | Where-Object mappingStatusChanged).Count
        durationChanged = @($modified | Where-Object durationChanged).Count
        kubejsCreateMapped = $customKubeJs.Count
    }
    added = $added
    removed = $removed
    modified = @($modified)
    unchangedRecipeIds = @($unchanged)
    kubejsCreateMappedRecipes = $customKubeJs
}
$report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding utf8

$standardAccounted = $report.summary.removed + $report.summary.modified +
        $report.summary.unchanged
$packAccounted = $report.summary.added + $report.summary.modified +
        $report.summary.unchanged
if ($report.standard.runtimeFingerprint -eq $report.pack.runtimeFingerprint -or
        $report.summary.added -lt 1 -or
        $standardAccounted -ne $report.summary.standardComparedRecipes -or
        $packAccounted -ne $report.summary.packComparedRecipes) {
    throw 'Runtime difference report failed fingerprint/change/accounting checks'
}

Write-Output "R09_RUNTIME_DIFFERENCE PASS standardTotal=$($report.standard.recipeManagerTotal) packTotal=$($report.pack.recipeManagerTotal) standardMilling=$($report.standard.milling) packMilling=$($report.pack.milling) standardPressing=$($report.standard.pressing) packPressing=$($report.pack.pressing) added=$($report.summary.added) removed=$($report.summary.removed) modified=$($report.summary.modified) unchanged=$($report.summary.unchanged) ingredientChanged=$($report.summary.ingredientChanged) outputChanged=$($report.summary.outputChanged) typeChanged=$($report.summary.typeChanged) mappingStatusChanged=$($report.summary.mappingStatusChanged) fingerprintsDiffer=true evidence=$OutputPath"
