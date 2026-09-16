$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$logDirectory = Join-Path $root 'work\logs'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$log = Join-Path $logDirectory ('build-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
& (Join-Path $PSScriptRoot 'Invoke-Gradle.ps1') clean build *>&1 | Tee-Object -FilePath $log

$libsDirectory = Join-Path $root 'forge-create-1.20.1\build\libs'
$binaryJars = @(Get-ChildItem -LiteralPath $libsDirectory -Filter '*.jar' -File |
    Where-Object Name -NotLike '*-sources.jar')
$sourceJars = @(Get-ChildItem -LiteralPath $libsDirectory -Filter '*-sources.jar' -File)
if ($binaryJars.Count -ne 1 -or $sourceJars.Count -ne 1) {
    throw "Expected exactly one Forge binary JAR and one sources JAR under $libsDirectory"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$runOnlyEntries = @(
    'journeymap/client/ui/fullscreen/Fullscreen.class'
    'journeymap/client/ui/fullscreen/Fullscreen.java'
)
foreach ($artifact in @($binaryJars[0], $sourceJars[0])) {
    $archive = [IO.Compression.ZipFile]::OpenRead($artifact.FullName)
    try {
        foreach ($runOnlyEntry in $runOnlyEntries) {
            if ($null -ne $archive.GetEntry($runOnlyEntry)) {
                throw "Run-only client acceptance target leaked into production artifact: $($artifact.FullName)"
            }
        }
    } finally {
        $archive.Dispose()
    }
}
Write-Output 'ARTIFACT_ISOLATION_VERIFIED createOnlyClientAcceptanceExcluded=true'
Write-Output "Build log: $log"
