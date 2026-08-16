[CmdletBinding()]
param(
    [switch]$KeepDatabase
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $projectRoot '.runtime'

function Stop-ManagedProcess([string]$pidFile, [string]$expectedCommand, [string]$name) {
    if (-not (Test-Path -LiteralPath $pidFile)) {
        Write-Host "$name is not recorded as running."
        return
    }

    $processId = Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue
    if ($processId -notmatch '^\d+$') {
        Remove-Item -LiteralPath $pidFile -Force
        Write-Warning "Removed invalid PID file for $name."
        return
    }

    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        Remove-Item -LiteralPath $pidFile -Force
        Write-Host "$name was already stopped."
        return
    }
    if ($process.CommandLine -notlike "*$expectedCommand*") {
        throw "PID $processId no longer belongs to $name; refusing to stop it."
    }

    Stop-Process -Id ([int]$processId)
    Remove-Item -LiteralPath $pidFile -Force
    Write-Host "$name stopped."
}

Set-Location $projectRoot
Stop-ManagedProcess (Join-Path $runtimeDir 'web.pid') 'vite' 'InsightOps Web'
Stop-ManagedProcess (Join-Path $runtimeDir 'worker.pid') 'insightops-worker' 'InsightOps Worker'
Stop-ManagedProcess (Join-Path $runtimeDir 'server.pid') 'insightops-server' 'InsightOps Server'

if (-not $KeepDatabase) {
    & docker compose --env-file .env -f infra/compose.yaml stop postgres
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to stop PostgreSQL.'
    }
    Write-Host 'InsightOps PostgreSQL stopped; its volume was preserved.'
}
else {
    Write-Host 'PostgreSQL kept running because -KeepDatabase was specified.'
}
