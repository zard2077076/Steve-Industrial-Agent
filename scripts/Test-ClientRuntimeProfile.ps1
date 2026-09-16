param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('neither-client', 'create-only-client')]
    [string]$Profile,

    [ValidateRange(60, 600)]
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$profilesRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run\profiles'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $profilesRoot $Profile))
$configDirectory = Join-Path $runDirectory 'config'
$logDirectory = Join-Path $root 'work\logs'

$rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$profilesPrefix = $profilesRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $runDirectory.StartsWith($profilesPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    -not $runDirectory.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
    $runDirectory -eq $profilesRoot) {
    throw "Refusing to use an unsafe client runtime profile directory: $runDirectory"
}

# Start from a new ignored profile directory so no stale log or crash can satisfy acceptance.
if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory, $configDirectory, $logDirectory | Out-Null
@(
    '# Generated for an isolated Steve Industrial Agent client runtime profile.'
    'earlyWindowHeight = 480'
    'versionCheck = false'
    'earlyWindowControl = true'
    'earlyWindowFBScale = 1'
    'earlyWindowProvider = "fmlearlywindow"'
    'earlyWindowWidth = 854'
    'earlyWindowMaximized = false'
    'defaultConfigPath = "defaultconfigs"'
    'disableOptimizedDFU = true'
    'earlyWindowSkipGLVersions = []'
    'earlyWindowLogHelpMessage = false'
    'maxThreads = -1'
    'earlyWindowSquir = false'
    'earlyWindowShowCPU = false'
) | Set-Content -LiteralPath (Join-Path $configDirectory 'fml.toml') -Encoding ascii
@(
    'version:3465'
    'onboardAccessibility:false'
    'skipMultiplayerWarning:true'
    'fullscreen:false'
    'overrideWidth:854'
    'overrideHeight:480'
    'lang:en_us'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'options.txt') -Encoding ascii

$started = Get-Date
$timestamp = $started.ToString('yyyyMMdd-HHmmss')
$log = Join-Path $logDirectory ("runtime-$Profile-$timestamp.log")
$stdout = Join-Path $logDirectory ("runtime-$Profile-$timestamp.stdout.tmp")
$stderr = Join-Path $logDirectory ("runtime-$Profile-$timestamp.stderr.tmp")
$invokeGradle = Join-Path $PSScriptRoot 'Invoke-Gradle.ps1'
$powerShell = (Get-Process -Id $PID).Path
$arguments = @(
    '-NoProfile'
    '-ExecutionPolicy'
    'Bypass'
    '-File'
    ('"' + $invokeGradle + '"')
    "-PindustrialRuntimeProfile=$Profile"
    ':forge-create-1.20.1:runClient'
)

$process = Start-Process -FilePath $powerShell `
    -ArgumentList $arguments `
    -WorkingDirectory $root `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr `
    -WindowStyle Hidden `
    -PassThru

$completedBeforeTimeout = $process.WaitForExit($TimeoutSeconds * 1000)
if (-not $completedBeforeTimeout) {
    & taskkill.exe /PID $process.Id /T /F | Out-Null
    $process.WaitForExit()
}

$combined = [Collections.Generic.List[string]]::new()
if (Test-Path -LiteralPath $stdout) {
    $combined.AddRange([string[]](Get-Content -LiteralPath $stdout))
}
if (Test-Path -LiteralPath $stderr) {
    $stderrLines = [string[]](Get-Content -LiteralPath $stderr)
    if ($stderrLines.Count -gt 0) {
        $combined.Add('===== STDERR =====')
        $combined.AddRange($stderrLines)
    }
}
$combined | Set-Content -LiteralPath $log -Encoding utf8
Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue

if (-not $completedBeforeTimeout) {
    throw "Client runtime profile exceeded the $TimeoutSeconds second wall-clock limit and its process tree was terminated. Log: $log"
}
if ($process.ExitCode -ne 0) {
    throw "Client runtime profile process failed with exit code $($process.ExitCode). Log: $log"
}

function Assert-LogContains([string]$Expected, [string]$Description) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $log. Expected: $Expected"
    }
}

Assert-LogContains 'java version 17.' 'Java 17 launch evidence'
Assert-LogContains 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge/Minecraft version evidence'
Assert-LogContains "CLIENT_RUNTIME_PROFILE_LIFECYCLE READY profile=$Profile point=SCREEN_RENDER_POST" 'post-render client lifecycle evidence'
Assert-LogContains "CLIENT_RUNTIME_PROFILE_AGENT READY profile=$Profile mod=steve_create_agent version=" 'Steve Industrial Agent client load evidence'
Assert-LogContains "CLIENT_RUNTIME_PROFILE_RESULT profile=$Profile mod=mekanism outcome=FAILURE code=UNSUPPORTED_RUNTIME detail=`"Optional mod is not loaded: mekanism`"" 'typed Mekanism-absent runtime adapter result'
Assert-LogContains "CLIENT_RUNTIME_PROFILE_EXIT REQUESTED profile=$Profile reason=verified" 'deterministic verified exit reason'
Assert-LogContains 'Stopping!' 'Minecraft clean-stop lifecycle evidence'
Assert-LogContains 'BUILD SUCCESSFUL' 'Gradle/client process clean exit'

switch ($Profile) {
    'neither-client' {
        Assert-LogContains 'CLIENT_RUNTIME_PROFILE_RESULT profile=neither-client mod=create outcome=FAILURE code=UNSUPPORTED_RUNTIME detail="Optional mod is not loaded: create"' 'typed Create-absent runtime adapter result'
        Assert-LogContains 'CLIENT_RUNTIME_PROFILE_SMOKE PASS profile=neither-client create=FAILURE:UNSUPPORTED_RUNTIME mekanism=FAILURE:UNSUPPORTED_RUNTIME' 'neither-client PASS marker'
        if (Select-String -LiteralPath $log -SimpleMatch 'Create 6.0.6 initializing!' -Quiet) {
            throw "Create unexpectedly initialized in neither-client profile: $log"
        }
    }
    'create-only-client' {
        Assert-LogContains 'Create 6.0.6 initializing!' 'Create 6.0.6 client initialization evidence'
        Assert-LogContains 'CLIENT_RUNTIME_PROFILE_OPTIONAL_COMPAT READY profile=create-only-client journeymapModLoaded=false targetSource=acceptance_only_unshipped' 'absent JourneyMap plus run-only optional-Mixin target evidence'
        Assert-LogContains 'CLIENT_RUNTIME_PROFILE_RESULT profile=create-only-client mod=create outcome=SUCCESS version=6.0.6-150 adapter=steve_industrial:forge_1_20_1_runtime_create' 'typed Create runtime adapter success result'
        Assert-LogContains 'CLIENT_RUNTIME_PROFILE_SMOKE PASS profile=create-only-client create=SUCCESS mekanism=FAILURE:UNSUPPORTED_RUNTIME' 'create-only-client PASS marker'
    }
}

$crashDirectory = Join-Path $runDirectory 'crash-reports'
$crashReports = @()
if (Test-Path -LiteralPath $crashDirectory) {
    $crashReports = @(Get-ChildItem -LiteralPath $crashDirectory -File)
}
if ($crashReports.Count -gt 0) {
    throw "Client runtime profile created crash report(s): $($crashReports.FullName -join ', ')"
}

$fatalPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]',
    'Exception in thread',
    'CLIENT_RUNTIME_PROFILE_SMOKE FAIL',
    'Preparing crash report',
    'ClassNotFoundException',
    'NoClassDefFoundError',
    'Mixin[^\r\n]*(error|failed|exception)',
    '(render|client)[^\r\n]*thread[^\r\n]*(error|failed|exception)'
)
$fatalMatches = @(Select-String -LiteralPath $log -Pattern $fatalPatterns -CaseSensitive:$false)
if ($fatalMatches.Count -gt 0) {
    throw "Client runtime profile log contains fatal/error evidence: $($fatalMatches.Line -join ' | ')"
}

$elapsedSeconds = [Math]::Round(((Get-Date) - $started).TotalSeconds, 1)
Write-Output "CLIENT_RUNTIME_PROFILE_VERIFIED profile=$Profile java=17 minecraft=1.20.1 forge=47.4.10 lifecycle=SCREEN_RENDER_POST autoExit=true wallClockSeconds=$elapsedSeconds crashReports=0"
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
