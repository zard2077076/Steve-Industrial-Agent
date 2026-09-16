param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$propertiesPath = Join-Path $root 'local.properties'
$local = @{}
if (Test-Path -LiteralPath $propertiesPath) {
    foreach ($line in Get-Content -LiteralPath $propertiesPath) {
        if ($line -match '^\s*([^#][^=]*)=(.*)$') {
            $local[$matches[1].Trim()] = $matches[2].Trim()
        }
    }
}

if ($local.ContainsKey('java_home')) {
    $env:JAVA_HOME = $local['java_home']
}
if (-not $env:JAVA_HOME) {
    throw 'JAVA_HOME is not set. Configure Java 17 in the environment or ignored local.properties.'
}
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
$env:GRADLE_USER_HOME = Join-Path $root 'work\gradle-home'

$gradle = Join-Path $root 'gradlew.bat'
if ($local.ContainsKey('gradle_executable')) {
    $gradle = $local['gradle_executable']
}
if ($local.ContainsKey('pcl2_root')) {
    $GradleArgs = @('-Ppcl2Root=' + $local['pcl2_root']) + $GradleArgs
}

Push-Location $root
try {
    & $gradle @GradleArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
