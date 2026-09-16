$ErrorActionPreference = 'Stop'

$minecraftVersion = '1.20.1'
$forgeVersion = '47.4.10'
$maxServerSeconds = 300
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$serverCache = Join-Path $repository "work\packaged-forge-server-$minecraftVersion-$forgeVersion"
$launchArgsSource = Join-Path $serverCache "libraries\net\minecraftforge\forge\$minecraftVersion-$forgeVersion\win_args.txt"

function Full([string]$PathValue) { [IO.Path]::GetFullPath($PathValue).TrimEnd('\') }
function Java-Path([string]$PathValue) { (Full $PathValue).Replace('\', '/') }
function Assert-OutsideRepository([string]$PathValue) {
    $candidate = Full $PathValue
    $prefix = (Full $repository) + '\'
    if ($candidate.Equals((Full $repository), [StringComparison]::OrdinalIgnoreCase) -or
            $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Public runtime acceptance must be outside the repository: $candidate"
    }
}
function Invoke-BoundedProcess(
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$WorkingDirectory,
        [int]$TimeoutSeconds) {
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in $ArgumentList) { [void]$startInfo.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { throw "Failed to start: $FilePath" }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        $process.Kill($true)
        $process.WaitForExit()
        throw "Process exceeded $TimeoutSeconds seconds: $FilePath"
    }
    $process.WaitForExit()
    $output = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    if (-not [string]::IsNullOrWhiteSpace($stderr)) {
        $output += [Environment]::NewLine + '--- STDERR ---' + [Environment]::NewLine + $stderr
    }
    [pscustomobject]@{ ExitCode=$process.ExitCode; Output=$output }
}
function Require-Log([string]$PathValue, [string]$Expected) {
    if (-not (Select-String -LiteralPath $PathValue -SimpleMatch $Expected -Quiet)) {
        throw "Missing acceptance evidence '$Expected' in $PathValue"
    }
}

if (-not (Test-Path -LiteralPath $launchArgsSource -PathType Leaf)) {
    throw 'Packaged Forge cache is absent. Run scripts/Test-PackagedNeitherServer.ps1 once.'
}
$localProperties = Join-Path $repository 'local.properties'
$javaHome = $null
foreach ($line in Get-Content -LiteralPath $localProperties) {
    if ($line -match '^\s*java_home=(.+)$') { $javaHome = $matches[1].Trim() }
}
if ([string]::IsNullOrWhiteSpace($javaHome)) { throw 'local.properties java_home is required' }
$java = Join-Path $javaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw "Java 17 not found: $java" }

& (Join-Path $PSScriptRoot 'Build.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Production build failed' }
$productionJars = @(Get-ChildItem -LiteralPath (Join-Path $repository 'forge-create-1.20.1\build\libs') `
        -Filter '*.jar' -File | Where-Object Name -NotLike '*-sources.jar')
if ($productionJars.Count -ne 1) { throw "Expected one production JAR, found $($productionJars.Count)" }
$productionJar = $productionJars[0]
$jarHash = (Get-FileHash -LiteralPath $productionJar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()

$acceptanceBase = Full (Join-Path ([IO.Path]::GetTempPath()) `
        ('SteveIndustrialAgent-PublicRuntime-' + [Guid]::NewGuid().ToString('N')))
$instanceRoot = Join-Path $acceptanceBase 'instance'
$backupRoot = Join-Path $acceptanceBase 'backups'
$importantRoot = Join-Path $acceptanceBase 'formal-reference-do-not-touch'
foreach ($pathValue in @($acceptanceBase, $instanceRoot, $backupRoot, $importantRoot)) {
    Assert-OutsideRepository $pathValue
    New-Item -ItemType Directory -Force -Path $pathValue | Out-Null
}

Copy-Item -LiteralPath (Join-Path $serverCache 'libraries') -Destination $instanceRoot -Recurse
$mods = Join-Path $instanceRoot 'mods'
$config = Join-Path $instanceRoot 'config'
$tools = Join-Path $instanceRoot 'tools'
New-Item -ItemType Directory -Force -Path $mods,$config,$tools | Out-Null
Copy-Item -LiteralPath $productionJar.FullName -Destination (Join-Path $mods $productionJar.Name)
$helper = Join-Path $tools 'Complete-IndustrialAgentBackup.ps1'
Copy-Item -LiteralPath (Join-Path $repository 'tools\Complete-IndustrialAgentBackup.ps1') -Destination $helper

'eula=true' | Set-Content -LiteralPath (Join-Path $instanceRoot 'eula.txt') -Encoding ascii
@(
    'online-mode=false'
    'server-port=0'
    'level-name=saves/public-alpha-world'
    'level-seed=steve-industrial-public-runtime-v1'
    'level-type=minecraft:flat'
    'generate-structures=false'
    'view-distance=3'
    'simulation-distance=3'
) | Set-Content -LiteralPath (Join-Path $instanceRoot 'server.properties') -Encoding ascii

$instanceJava = Java-Path $instanceRoot
$backupJava = Java-Path $backupRoot
$importantJava = Java-Path $importantRoot
@(
    '# Generated acceptance configuration: paths are disposable and repository-external.'
    '[runtime]'
    'configSchemaVersion = 1'
    'runtimeMode = "DISPOSABLE_TEST_WORLD"'
    "testInstanceRoot = `"$instanceJava`""
    'allowedTestWorlds = ["saves/public-alpha-world"]'
    "backupRoot = `"$backupJava`""
    "importantInstanceRoots = [`"$importantJava`"]"
    'maxRegionSize = 32'
    'requireBackup = true'
    'requireWorldMarker = true'
    'allowDirectPilot = true'
    'diagnosticsRedaction = true'
    'formalWorldPolicy = "DENY_CONFIGURED_ROOTS"'
) | Set-Content -LiteralPath (Join-Path $config 'steve-industrial-agent-common.toml') -Encoding utf8

function Invoke-Phase([string]$Phase) {
    @(
        '-Xms512M'
        '-Xmx2G'
        "-Dsteve_industrial.test.publicRuntimeAcceptancePhase=$Phase"
        '-Dterminal.jline=false'
        '-Dterminal.ansi=false'
    ) | Set-Content -LiteralPath (Join-Path $instanceRoot 'user_jvm_args.txt') -Encoding ascii
    $result = Invoke-BoundedProcess -FilePath $java -WorkingDirectory $instanceRoot `
            -TimeoutSeconds $maxServerSeconds -ArgumentList @(
                '@user_jvm_args.txt'
                "@libraries/net/minecraftforge/forge/$minecraftVersion-$forgeVersion/win_args.txt"
                '--nogui')
    $log = Join-Path $acceptanceBase ("public-runtime-$Phase.log")
    $result.Output | Set-Content -LiteralPath $log -Encoding utf8
    if ($result.ExitCode -ne 0) { throw "Public runtime $Phase phase exited $($result.ExitCode): $log" }
    Require-Log $log ("PUBLIC_RUNTIME_" + $Phase.ToUpperInvariant() + ' PASS')
    Require-Log $log 'ThreadedAnvilChunkStorage: All dimensions are saved'
    return $log
}

$prepareLog = Invoke-Phase 'prepare'
$requests = @(Get-ChildItem -LiteralPath (Join-Path $backupRoot 'requests') -Filter '*.json' -File)
if ($requests.Count -ne 1) { throw "Expected one stopped-game backup request, found $($requests.Count)" }
$helperResult = Invoke-BoundedProcess -FilePath 'powershell.exe' -WorkingDirectory $instanceRoot `
        -TimeoutSeconds 300 -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',$helper,'-RequestFile',$requests[0].FullName)
$helperLog = Join-Path $acceptanceBase 'portable-backup-helper.log'
$helperResult.Output | Set-Content -LiteralPath $helperLog -Encoding utf8
if ($helperResult.ExitCode -ne 0) { throw "Portable backup helper failed: $helperLog" }
Require-Log $helperLog 'PORTABLE_BACKUP_COMPLETE'

$verifyLog = Invoke-Phase 'verify'
if (@(Get-ChildItem -LiteralPath $importantRoot -Force).Count -ne 0) {
    throw 'The configured important-instance sentinel was touched'
}
$crashes = @()
if (Test-Path -LiteralPath (Join-Path $instanceRoot 'crash-reports')) {
    $crashes = @(Get-ChildItem -LiteralPath (Join-Path $instanceRoot 'crash-reports') -File)
}
if ($crashes.Count -ne 0) { throw "Public runtime created crash reports: $($crashes.FullName -join ', ')" }

$marker = "PUBLIC_RUNTIME_RELEASE_VERIFIED jarSha256=$jarHash configSource=forge-file markerPersistent=true portableBackup=true restartVerify=true sourcePrePostEqual=true restoreDrill=true repositoryRuntimeDependency=false importantRootTouched=false crashReports=0"
$marker | Set-Content -LiteralPath (Join-Path $acceptanceBase 'VERIFIED.txt') -Encoding ascii
Write-Output $marker
Write-Output "Acceptance root: $acceptanceBase"
Write-Output "Prepare log: $prepareLog"
Write-Output "Helper log: $helperLog"
Write-Output "Verify log: $verifyLog"
