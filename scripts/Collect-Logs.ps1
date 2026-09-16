$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$run = Join-Path $root 'forge-create-1.20.1\run'
$out = Join-Path $root ('work\logs\scan-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
$candidates = @(
    (Join-Path $run 'logs\latest.log'),
    (Join-Path $run 'logs\debug.log')
)
$patterns = 'Exception|ERROR|FATAL|Mixin.*failed|crash|timed out|server thread|client thread'
$matches = foreach ($file in $candidates) {
    if (Test-Path -LiteralPath $file) {
        Select-String -LiteralPath $file -Pattern $patterns -CaseSensitive:$false
    }
}
$matches | Set-Content -LiteralPath $out -Encoding UTF8
Write-Output $out
