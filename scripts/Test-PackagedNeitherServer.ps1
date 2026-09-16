$ErrorActionPreference = 'Stop'

$minecraftVersion = '1.20.1'
$forgeVersion = '47.4.10'
$installerSha1 = '66BFEA9963BFA60D88BAB6B2750E74A958392715'
$installerUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/$minecraftVersion-$forgeVersion/forge-$minecraftVersion-$forgeVersion-installer.jar"
$maxInstallSeconds = 600
$maxServerSeconds = 300

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = [IO.Path]::GetFullPath((Join-Path $root 'forge-create-1.20.1\run'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $runRoot 'packaged-neither-server'))
$workRoot = [IO.Path]::GetFullPath((Join-Path $root 'work'))
$bootstrapRoot = [IO.Path]::GetFullPath((Join-Path $workRoot 'packaged-server-bootstrap'))
$serverCache = [IO.Path]::GetFullPath((Join-Path $workRoot "packaged-forge-server-$minecraftVersion-$forgeVersion"))
$logDirectory = Join-Path $workRoot 'logs'

function Assert-SafeChildPath(
        [string]$Path,
        [string]$Parent,
        [string]$Description) {
    $pathValue = [IO.Path]::GetFullPath($Path)
    $parentValue = [IO.Path]::GetFullPath($Parent)
    $prefix = $parentValue.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $pathValue.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -or
            $pathValue -eq $parentValue) {
        throw "Refusing to use unsafe $Description path: $pathValue"
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
    foreach ($argument in $ArgumentList) {
        [void]$startInfo.ArgumentList.Add($argument)
    }

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw "Failed to start bounded process: $FilePath"
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        $process.Kill($true)
        $process.WaitForExit()
        throw "Process exceeded the $TimeoutSeconds-second wall-clock limit: $FilePath"
    }
    $process.WaitForExit()

    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    $output = $stdout
    if (-not [string]::IsNullOrWhiteSpace($stderr)) {
        $output += [Environment]::NewLine + '--- STDERR ---' + [Environment]::NewLine + $stderr
    }
    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        Output = $output
    }
}

function Read-LocalProperties {
    $properties = @{}
    $path = Join-Path $root 'local.properties'
    if (Test-Path -LiteralPath $path) {
        foreach ($line in Get-Content -LiteralPath $path) {
            if ($line -match '^\s*([^#][^=]*)=(.*)$') {
                $properties[$matches[1].Trim()] = $matches[2].Trim()
            }
        }
    }
    return $properties
}

function Assert-LogContains(
        [string]$Log,
        [string]$Expected,
        [string]$Description) {
    if (-not (Select-String -LiteralPath $Log -SimpleMatch $Expected -Quiet)) {
        throw "Missing $Description in $Log. Expected: $Expected"
    }
}

function Assert-OptionalDependency(
        [string]$ModsToml,
        [string]$ModId) {
    $blocks = [regex]::Matches(
            $ModsToml,
            '(?ms)^\[\[dependencies\.steve_create_agent\]\]\s*(.*?)(?=^\[\[|\z)')
    $block = $blocks | Where-Object {
        $_.Groups[1].Value -match ('(?m)^modId="{0}"$' -f [regex]::Escape($ModId))
    }
    if ($null -eq $block -or $block.Groups[1].Value -notmatch '(?m)^mandatory=false$') {
        throw "Production mods.toml does not declare $ModId as optional"
    }
}

Assert-SafeChildPath $runDirectory $runRoot 'packaged runtime'
Assert-SafeChildPath $bootstrapRoot $workRoot 'installer cache'
Assert-SafeChildPath $serverCache $workRoot 'Forge server cache'
New-Item -ItemType Directory -Force -Path $bootstrapRoot, $logDirectory | Out-Null

$local = Read-LocalProperties
if (-not $local.ContainsKey('java_home')) {
    throw 'Configure ignored local.properties java_home before packaged-server acceptance'
}
$java = Join-Path $local['java_home'] 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) {
    throw "Configured Java executable does not exist: $java"
}
$javaVersion = & $java -version 2>&1
if ($LASTEXITCODE -ne 0 -or ($javaVersion -join ' ') -notmatch 'version "17\.') {
    throw "Packaged-server acceptance requires Java 17: $($javaVersion -join ' ')"
}

# Produce the actual reobfuscated binary and sources artifacts before selecting the
# one production binary that will be copied into the isolated server's mods folder.
& (Join-Path $PSScriptRoot 'Build.ps1')

$libsDirectory = Join-Path $root 'forge-create-1.20.1\build\libs'
$productionJars = @(Get-ChildItem -LiteralPath $libsDirectory -Filter '*.jar' -File |
    Where-Object Name -NotLike '*-sources.jar')
if ($productionJars.Count -ne 1) {
    throw "Expected exactly one packaged production JAR, found $($productionJars.Count)"
}
$productionJar = $productionJars[0]
$productionJarHash = (Get-FileHash -LiteralPath $productionJar.FullName -Algorithm SHA256).Hash

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($productionJar.FullName)
try {
    $requiredEntries = @(
        'dev/stevecreate/agent/core/model/ResourceId.class'
        'dev/stevecreate/agent/adapter/api/IndustrialModAdapter.class'
        'dev/stevecreate/agent/forge1201/SteveIndustrialAgentMod.class'
        'dev/stevecreate/agent/forge1201/adapter/create/ForgeCreatePlanAdapter.class'
        'META-INF/mods.toml'
    )
    foreach ($entryName in $requiredEntries) {
        if ($null -eq $archive.GetEntry($entryName)) {
            throw "Production JAR is missing required release entry: $entryName"
        }
    }
    $forbiddenEntries = @($archive.Entries | Where-Object {
        $_.FullName.StartsWith('com/simibubi/create/', [StringComparison]::Ordinal) -or
        $_.FullName.StartsWith('mekanism/', [StringComparison]::Ordinal) -or
        $_.FullName -eq 'journeymap/client/ui/fullscreen/Fullscreen.class'
    })
    if ($forbiddenEntries.Count -gt 0) {
        throw "Production JAR embeds forbidden runtime dependency classes: $($forbiddenEntries.FullName -join ', ')"
    }

    $reader = [IO.StreamReader]::new($archive.GetEntry('META-INF/mods.toml').Open())
    try {
        $modsToml = $reader.ReadToEnd()
    } finally {
        $reader.Dispose()
    }
    Assert-OptionalDependency $modsToml 'create'
    Assert-OptionalDependency $modsToml 'mekanism'
} finally {
    $archive.Dispose()
}

$installer = Join-Path $bootstrapRoot "forge-$minecraftVersion-$forgeVersion-installer.jar"
if (-not (Test-Path -LiteralPath $installer) -or
        (Get-FileHash -LiteralPath $installer -Algorithm SHA1).Hash -ne $installerSha1) {
    Invoke-WebRequest -UseBasicParsing -Uri $installerUrl -OutFile $installer -TimeoutSec 120
}
$actualInstallerSha1 = (Get-FileHash -LiteralPath $installer -Algorithm SHA1).Hash
if ($actualInstallerSha1 -ne $installerSha1) {
    throw "Forge installer SHA-1 mismatch: expected=$installerSha1 actual=$actualInstallerSha1"
}

$cacheMarker = Join-Path $serverCache 'installer-sha1.txt'
$cacheLaunchArgs = Join-Path $serverCache "libraries\net\minecraftforge\forge\$minecraftVersion-$forgeVersion\win_args.txt"
if (-not (Test-Path -LiteralPath $cacheMarker) -or
        (Get-Content -LiteralPath $cacheMarker -Raw).Trim() -ne $installerSha1 -or
        -not (Test-Path -LiteralPath $cacheLaunchArgs)) {
    if (Test-Path -LiteralPath $serverCache) {
        Remove-Item -LiteralPath $serverCache -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $serverCache | Out-Null
    $installResult = Invoke-BoundedProcess `
            -FilePath $java `
            -ArgumentList @('-jar', $installer, '--installServer', '.') `
            -WorkingDirectory $serverCache `
            -TimeoutSeconds $maxInstallSeconds
    $installLog = Join-Path $logDirectory ('packaged-forge-install-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
    $installResult.Output | Set-Content -LiteralPath $installLog -Encoding utf8
    if ($installResult.ExitCode -ne 0 -or
            -not (Select-String -LiteralPath $installLog -SimpleMatch 'The server installed successfully' -Quiet) -or
            -not (Test-Path -LiteralPath $cacheLaunchArgs)) {
        throw "Forge server installation failed; inspect $installLog"
    }
    $installerSha1 | Set-Content -LiteralPath $cacheMarker -Encoding ascii
}

if (Test-Path -LiteralPath $runDirectory) {
    Remove-Item -LiteralPath $runDirectory -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null
Copy-Item -LiteralPath (Join-Path $serverCache 'libraries') -Destination $runDirectory -Recurse

$modsDirectory = Join-Path $runDirectory 'mods'
$configDirectory = Join-Path $runDirectory 'config'
New-Item -ItemType Directory -Force -Path $modsDirectory, $configDirectory | Out-Null
$packagedJar = Join-Path $modsDirectory $productionJar.Name
Copy-Item -LiteralPath $productionJar.FullName -Destination $packagedJar
$packagedJarHash = (Get-FileHash -LiteralPath $packagedJar -Algorithm SHA256).Hash
if ($packagedJarHash -ne $productionJarHash) {
    throw 'Packaged runtime JAR hash differs from the clean-build production JAR'
}
$installedMods = @(Get-ChildItem -LiteralPath $modsDirectory -File)
if ($installedMods.Count -ne 1 -or $installedMods[0].Name -ne $productionJar.Name) {
    throw "Packaged neither-mod profile contains unexpected mod JARs: $($installedMods.Name -join ', ')"
}

@('eula=true') | Set-Content -LiteralPath (Join-Path $runDirectory 'eula.txt') -Encoding ascii
@(
    'online-mode=false'
    'server-port=0'
    'level-name=world'
    'level-seed=steve-industrial-packaged-neither-v1'
    'level-type=minecraft:flat'
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    'generate-structures=false'
    'view-distance=3'
    'simulation-distance=3'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'server.properties') -Encoding ascii
@(
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
    '-Xms512M'
    '-Xmx2G'
    '-Dsteve_industrial.test.runtimeProfile=neither-server'
    '-Dterminal.jline=false'
    '-Dterminal.ansi=false'
) | Set-Content -LiteralPath (Join-Path $runDirectory 'user_jvm_args.txt') -Encoding ascii

$started = Get-Date
$log = Join-Path $logDirectory ('packaged-neither-server-' + $started.ToString('yyyyMMdd-HHmmss') + '.log')
$serverResult = Invoke-BoundedProcess `
        -FilePath $java `
        -ArgumentList @(
            '@user_jvm_args.txt',
            "@libraries/net/minecraftforge/forge/$minecraftVersion-$forgeVersion/win_args.txt",
            '--nogui') `
        -WorkingDirectory $runDirectory `
        -TimeoutSeconds $maxServerSeconds
@(
    "PACKAGED_JAR_INPUT name=$($productionJar.Name) bytes=$($productionJar.Length) sha256=$productionJarHash"
    "PACKAGED_FORGE_INSTALLER sha1=$installerSha1"
    $serverResult.Output
) | Set-Content -LiteralPath $log -Encoding utf8
if ($serverResult.ExitCode -ne 0) {
    throw "Packaged neither-mod server exited $($serverResult.ExitCode); inspect $log"
}

Assert-LogContains $log 'java version 17.' 'Java 17 launch evidence'
Assert-LogContains $log 'Forge mod loading, version 47.4.10, for MC 1.20.1' 'Forge/Minecraft launch evidence'
Assert-LogContains $log 'RUNTIME_PROFILE_RESULT profile=neither-server mod=create outcome=FAILURE code=UNSUPPORTED_RUNTIME detail="Optional mod is not loaded: create"' 'typed Create absence'
Assert-LogContains $log 'RUNTIME_PROFILE_RESULT profile=neither-server mod=mekanism outcome=FAILURE code=UNSUPPORTED_RUNTIME detail="Optional mod is not loaded: mekanism"' 'typed Mekanism absence'
Assert-LogContains $log 'RUNTIME_PROFILE_SMOKE PASS profile=neither-server create=FAILURE:UNSUPPORTED_RUNTIME mekanism=FAILURE:UNSUPPORTED_RUNTIME' 'packaged neither-mod PASS marker'
Assert-LogContains $log 'ThreadedAnvilChunkStorage: All dimensions are saved' 'clean server shutdown evidence'
if (Select-String -LiteralPath $log -SimpleMatch 'Create 6.0.6 initializing!' -Quiet) {
    throw "Create unexpectedly initialized in packaged neither-mod profile: $log"
}

$crashReports = @()
$crashDirectory = Join-Path $runDirectory 'crash-reports'
if (Test-Path -LiteralPath $crashDirectory) {
    $crashReports = @(Get-ChildItem -LiteralPath $crashDirectory -File)
}
if ($crashReports.Count -gt 0) {
    throw "Packaged neither-mod profile created crash report(s): $($crashReports.FullName -join ', ')"
}

$forbiddenPatterns = @(
    '\[[^\]]+/(ERROR|FATAL)\]'
    'Exception in thread'
    'ClassNotFoundException'
    'NoClassDefFoundError'
    'Mixin[^\r\n]*(fail|error|exception)'
    'Preparing crash report'
    'RUNTIME_PROFILE_SMOKE FAIL'
    'ModLoadingException'
)
$forbiddenMatches = @(Select-String -LiteralPath $log -Pattern $forbiddenPatterns -CaseSensitive:$false)
if ($forbiddenMatches.Count -gt 0) {
    throw "Packaged neither-mod log contains forbidden evidence: $($forbiddenMatches.Line -join ' | ')"
}

$marker = "PACKAGED_NEITHER_SERVER_VERIFIED jar=$($productionJar.Name) sha256=$productionJarHash minecraft=$minecraftVersion forge=$forgeVersion create=FAILURE:UNSUPPORTED_RUNTIME mekanism=FAILURE:UNSUPPORTED_RUNTIME boundedSeconds=$maxServerSeconds cleanExit=true crashReports=0"
$marker | Add-Content -LiteralPath $log -Encoding utf8
Write-Output $marker
Write-Output "Isolated run directory: $runDirectory"
Write-Output "Test log: $log"
