param(
    [string]$JavaHome = $env:JAVA_HOME,
    [Parameter(Mandatory = $true)][string]$GradleExecutable,
    [Parameter(Mandatory = $true)][string]$RootDirectory
)

$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$work = (Resolve-Path (Join-Path $repository 'work')).Path
$evidenceDirectory = Join-Path $work 'release-evidence'
$root = [IO.Path]::GetFullPath($RootDirectory)

function Assert-ChildOfWork([string]$Path, [string]$Code) {
    if (-not $Path.StartsWith($work + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "${Code}: $Path"
    }
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

Assert-ChildOfWork $root 'ONLINE_ROOT_OUTSIDE_WORK'
if (Test-Path -LiteralPath $root) {
    throw "ONLINE_ROOT_MUST_BE_NEW: $root"
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'ONLINE_JAVA_17_REQUIRED'
}
if (-not (Test-Path -LiteralPath $GradleExecutable -PathType Leaf)) {
    throw "ONLINE_GRADLE_NOT_FOUND: $GradleExecutable"
}
$javaVersion = & (Join-Path $JavaHome 'bin\java.exe') -version 2>&1
if (($javaVersion -join "`n") -notmatch 'version "17\.') {
    throw 'ONLINE_JAVA_VERSION_MISMATCH'
}
if ((& git -C $repository status --porcelain --untracked-files=normal) -ne $null) {
    throw 'ONLINE_REPOSITORY_NOT_CLEAN'
}

New-Item -ItemType Directory -Force -Path $root | Out-Null
$source = Join-Path $root 'source-online'
$gradleHome = Join-Path $root 'gradle-user-home-online'
$mavenLocal = Join-Path $root 'maven-repository-online'
$buildLog = Join-Path $root 'online-clean-build.log'
& git clone --quiet --no-hardlinks --local $repository $source
if ($LASTEXITCODE -ne 0) { throw 'ONLINE_CLEAN_CLONE_FAILED' }
if (Test-Path -LiteralPath (Join-Path $source 'local.properties')) {
    throw 'ONLINE_LOCAL_PROPERTIES_LEAK'
}
if (Test-Path -LiteralPath (Join-Path $source 'work\local-maven')) {
    throw 'ONLINE_DEVELOPER_MAVEN_LEAK'
}
if (Test-Path -LiteralPath $gradleHome) {
    throw 'ONLINE_GRADLE_HOME_NOT_FRESH'
}
if (Test-Path -LiteralPath $mavenLocal) {
    throw 'ONLINE_MAVEN_REPOSITORY_NOT_FRESH'
}
New-Item -ItemType Directory -Force -Path $mavenLocal | Out-Null

$oldJava = $env:JAVA_HOME
$oldGradle = $env:GRADLE_USER_HOME
try {
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = $gradleHome
    Push-Location $source
    try {
        & $GradleExecutable "-Dmaven.repo.local=$mavenLocal" --no-daemon --console=plain --info --refresh-dependencies clean build *> $buildLog
        $buildExit = $LASTEXITCODE
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $oldJava
    $env:GRADLE_USER_HOME = $oldGradle
}
if ($buildExit -ne 0) {
    Get-Content -LiteralPath $buildLog -Tail 120
    throw "ONLINE_COLD_BUILD_FAILED: exit=$buildExit"
}
$externalMavenCache = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2'
if ((Get-Content -LiteralPath $buildLog -Raw) -match [regex]::Escape($externalMavenCache)) {
    throw "ONLINE_EXTERNAL_MAVEN_CACHE_USED: $externalMavenCache"
}

$jar = Join-Path $source 'forge-create-1.20.1\build\libs\steve-industrial-agent-0.1.0-alpha.1.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'ONLINE_RELEASE_JAR_MISSING' }
$head = (& git -C $repository rev-parse HEAD).Trim()
$cloneHead = (& git -C $source rev-parse HEAD).Trim()
if ($head -ne $cloneHead) { throw 'ONLINE_CLONE_COMMIT_MISMATCH' }
if ((& git -C $source status --porcelain --untracked-files=normal) -ne $null) {
    throw 'ONLINE_CLONE_DIRTY_AFTER_BUILD'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
    if ($null -eq $entry) { throw 'ONLINE_RELEASE_MANIFEST_MISSING' }
    $reader = [IO.StreamReader]::new($entry.Open())
    try { $releaseManifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally { $archive.Dispose() }
if ($releaseManifest -notmatch '(?m)^Dirty-Worktree: false\r?$' -or
        $releaseManifest -notmatch "(?m)^Git-Commit: $([regex]::Escape($head))\r?$") {
    throw 'ONLINE_RELEASE_MANIFEST_NOT_CLEAN_CURRENT_HEAD'
}

$urls = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$urlRegex = [regex]'https?://[^\s\]\[\)\(''"<>]+'
foreach ($line in Get-Content -LiteralPath $buildLog) {
    foreach ($match in $urlRegex.Matches($line)) {
        $candidate = $match.Value.TrimEnd('.', ',', ';')
        try {
            $uri = [Uri]$candidate
            if ($uri.UserInfo) { throw "ONLINE_CREDENTIAL_BEARING_URL: $candidate" }
            [void]$urls.Add($uri.AbsoluteUri)
        } catch [UriFormatException] { }
    }
}

$modulesRoot = Join-Path $gradleHome 'caches\modules-2\files-2.1'
if (-not (Test-Path -LiteralPath $modulesRoot -PathType Container)) {
    throw 'ONLINE_MODULE_CACHE_MISSING'
}
$dependencies = [Collections.Generic.List[object]]::new()
foreach ($file in Get-ChildItem -LiteralPath $modulesRoot -Recurse -File | Sort-Object FullName) {
    $relative = $file.FullName.Substring($modulesRoot.Length + 1).Replace('\', '/')
    $parts = $relative.Split('/')
    if ($parts.Count -lt 5) { throw "ONLINE_UNEXPECTED_MODULE_CACHE_PATH: $relative" }
    $group = $parts[0]
    $module = $parts[1]
    $moduleVersion = $parts[2]
    $expectedSuffix = '/' + $group.Replace('.', '/') + '/' + $module + '/' + $moduleVersion + '/' + $file.Name
    $matchedUrls = @($urls | Where-Object {
        try { ([Uri]$_).AbsolutePath.EndsWith($expectedSuffix, [StringComparison]::Ordinal) }
        catch { $false }
    } | Sort-Object -Unique)
    $dependencies.Add([ordered]@{
        group=$group; module=$module; version=$moduleVersion; artifact=$file.Name
        cachePath=$relative; bytes=$file.Length; sha256=Get-Sha256 $file.FullName
        observedSourceUrls=$matchedUrls
        sourceAttribution=if ($matchedUrls.Count -gt 0) { 'observed-online-download' } else { 'fresh-cache-derived-or-log-not-emitted' }
    })
}

$origins = @($urls | ForEach-Object { ([Uri]$_).GetLeftPart([UriPartial]::Authority) } | Sort-Object -Unique)
$dependencyManifest = [ordered]@{
    schema='steve-industrial:dependency-resolution/v1'; gitCommit=$head
    freshGradleUserHome=$true; online=$true; cleanBuild=$true
    noPriorGradleCache=$true; isolatedMavenRepository=$mavenLocal
    noPriorMavenCache=$true; localPropertiesPresent=$false
    developerLocalMavenPresent=$false; pcl2PropertyUsed=$false
    declaredRepositories=@(
        'https://plugins.gradle.org/m2/', 'https://maven.minecraftforge.net/',
        'https://maven.parchmentmc.org/', 'https://repo.maven.apache.org/maven2/',
        'https://maven.createmod.net/', 'https://maven.ithundxr.dev/mirror/',
        'https://raw.githubusercontent.com/Fuzss/modresources/main/maven/'
    )
    observedOrigins=$origins; observedUrlCount=$urls.Count
    dependencyFileCount=$dependencies.Count; dependencies=$dependencies
}
$dependencyManifestPath = Join-Path $root 'online-dependency-manifest.json'
$dependencyManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $dependencyManifestPath -Encoding utf8

$result = [ordered]@{
    schema='steve-industrial:online-dependency-resolution/v1'; status='PASS'; gitCommit=$head
    source=$source; gradleUserHome=$gradleHome; gradleUserHomeWasNew=$true
    isolatedMavenRepository=$mavenLocal; isolatedMavenRepositoryWasNew=$true
    onlineColdCleanBuild=$true; oldCacheUsed=$false; developerEnvironmentDependency=$false
    javaMajor=17; gradle='8.4'; buildLog=$buildLog
    dependencyManifest=$dependencyManifestPath
    dependencyManifestSha256=Get-Sha256 $dependencyManifestPath
    dependencyFileCount=$dependencies.Count; observedOrigins=$origins
    jar=$jar; jarSha256=Get-Sha256 $jar; jarBytes=(Get-Item -LiteralPath $jar).Length
    completedAtUtc=(Get-Date).ToUniversalTime().ToString('o')
}
$resultPath = Join-Path $root 'online-dependency-resolution.json'
$result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $resultPath -Encoding utf8
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath `
        (Join-Path $evidenceDirectory 'online-dependency-resolution-current.json') -Encoding utf8
Write-Output "ONLINE_DEPENDENCY_RESOLUTION PASS commit=$head dependencies=$($dependencies.Count) origins=$($origins.Count) jarSha256=$($result.jarSha256) freshGradleUserHome=true oldCacheUsed=false developerEnvironmentDependency=false"
