[CmdletBinding()]
param([ValidateRange(5, 600)][int]$TimeoutSeconds = 120)

$ErrorActionPreference = 'Stop'
$docker = (Get-Command docker.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $docker) { throw 'Docker Desktop command is unavailable. Check the local Docker Desktop installation.' }

function Test-LocalEngine {
    try {
        & $docker --context desktop-linux version --format '{{.Server.Version}}' 2>$null | Out-Null
        return $LASTEXITCODE -eq 0
    } catch {
        return $false
    }
}

if (Test-LocalEngine) { return }

if (-not (Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue)) {
    $cliDirectory = Split-Path -Parent $docker
    $applicationDirectory = Split-Path -Parent (Split-Path -Parent $cliDirectory)
    $candidates = @(
        (Join-Path $applicationDirectory 'Docker Desktop.exe'),
        (Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe')
    )
    $desktop = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $desktop) { throw 'Docker Desktop executable was not found in the local installation.' }
    Start-Process -FilePath $desktop -WindowStyle Hidden
    Write-Host 'Starting local Docker Desktop...'
} else {
    Write-Host 'Waiting for the local Docker engine...'
}

$clock = [Diagnostics.Stopwatch]::StartNew()
while ($clock.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
    if (Test-LocalEngine) { return }
    Start-Sleep -Seconds 2
}
throw "The local Docker engine did not start within $TimeoutSeconds seconds. Check Docker Desktop for startup errors."
