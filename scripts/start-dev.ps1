[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDir = Join-Path $projectRoot '.runtime'
$webRoot = Join-Path $projectRoot 'insightops-web'
$serverJar = Join-Path $projectRoot 'insightops-server\target\insightops-server-0.1.0-SNAPSHOT.jar'
$workerJar = Join-Path $projectRoot 'insightops-worker\target\insightops-worker-0.1.0-SNAPSHOT.jar'

function Resolve-RequiredCommand([string]$name) {
    $command = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $command) {
        throw "Required command is unavailable: $name"
    }
    return $command.Source
}

function Assert-PortAvailable([int]$port) {
    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -ne $listener) {
        throw "Port $port is already in use by PID $($listener.OwningProcess). Run scripts\stop-dev.ps1 or stop that process first."
    }
}

function Remove-StalePidFile([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) {
        return
    }
    $processId = Get-Content -LiteralPath $path -ErrorAction SilentlyContinue
    if ($processId -match '^\d+$' -and $null -ne (Get-Process -Id ([int]$processId) -ErrorAction SilentlyContinue)) {
        throw "A managed process is already running with PID $processId. Run scripts\stop-dev.ps1 first."
    }
    Remove-Item -LiteralPath $path -Force
}

function Wait-ForHttp([string]$url, [int]$attempts) {
    for ($attempt = 1; $attempt -le $attempts; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 2
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return
            }
        }
        catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "Service did not become ready: $url. Check .runtime logs."
}

Set-Location $projectRoot

$javaCommand = Resolve-RequiredCommand 'java'
$mavenCommand = Resolve-RequiredCommand 'mvn'
$nodeCommand = Resolve-RequiredCommand 'node'
$npmCommand = Resolve-RequiredCommand 'npm.cmd'
$null = Resolve-RequiredCommand 'docker'

if (-not (Test-Path -LiteralPath (Join-Path $projectRoot '.env'))) {
    throw 'Missing .env. Copy .env.example to .env and configure local values first.'
}

$environmentText = Get-Content -Raw -LiteralPath (Join-Path $projectRoot '.env')
if ($environmentText -notmatch '(?m)^DEEPSEEK_API_KEY=.+$') {
    Write-Warning 'DEEPSEEK_API_KEY is not configured. The application can start, but model chat will not be ready.'
}

New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
$serverPidFile = Join-Path $runtimeDir 'server.pid'
$workerPidFile = Join-Path $runtimeDir 'worker.pid'
$webPidFile = Join-Path $runtimeDir 'web.pid'
Remove-StalePidFile $serverPidFile
Remove-StalePidFile $workerPidFile
Remove-StalePidFile $webPidFile
Assert-PortAvailable 18080
Assert-PortAvailable 18081
Assert-PortAvailable 15173

& docker compose --env-file .env -f infra/compose.yaml up -d
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to start PostgreSQL with Docker Compose.'
}

if (-not $SkipBuild) {
    & $mavenCommand -pl insightops-server,insightops-worker -am package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw 'Backend build failed.'
    }
}
if (-not (Test-Path -LiteralPath $serverJar)) {
    throw "Server JAR not found: $serverJar"
}
if (-not (Test-Path -LiteralPath $workerJar)) {
    throw "Worker JAR not found: $workerJar"
}

if (-not (Test-Path -LiteralPath (Join-Path $webRoot 'node_modules'))) {
    if ($SkipInstall) {
        throw 'Frontend dependencies are missing and -SkipInstall was specified.'
    }
    Push-Location $webRoot
    try {
        & $npmCommand ci
        if ($LASTEXITCODE -ne 0) {
            throw 'Frontend dependency installation failed.'
        }
    }
    finally {
        Pop-Location
    }
}

$serverProcess = Start-Process -FilePath $javaCommand `
    -ArgumentList @('-jar', $serverJar) `
    -WorkingDirectory $projectRoot `
    -RedirectStandardOutput (Join-Path $runtimeDir 'server.out.log') `
    -RedirectStandardError (Join-Path $runtimeDir 'server.err.log') `
    -WindowStyle Hidden `
    -PassThru
$serverProcess.Id | Set-Content -LiteralPath $serverPidFile

$workerProcess = Start-Process -FilePath $javaCommand `
    -ArgumentList @('-jar', $workerJar) `
    -WorkingDirectory $projectRoot `
    -RedirectStandardOutput (Join-Path $runtimeDir 'worker.out.log') `
    -RedirectStandardError (Join-Path $runtimeDir 'worker.err.log') `
    -WindowStyle Hidden `
    -PassThru
$workerProcess.Id | Set-Content -LiteralPath $workerPidFile

$viteScript = Join-Path $webRoot 'node_modules\vite\bin\vite.js'
$webProcess = Start-Process -FilePath $nodeCommand `
    -ArgumentList @($viteScript, '--host', '127.0.0.1') `
    -WorkingDirectory $webRoot `
    -RedirectStandardOutput (Join-Path $runtimeDir 'web.out.log') `
    -RedirectStandardError (Join-Path $runtimeDir 'web.err.log') `
    -WindowStyle Hidden `
    -PassThru
$webProcess.Id | Set-Content -LiteralPath $webPidFile

Wait-ForHttp 'http://127.0.0.1:18080/actuator/health' 60
Wait-ForHttp 'http://127.0.0.1:18081/actuator/health' 60
Wait-ForHttp 'http://127.0.0.1:15173/' 40

Write-Host 'InsightOps Agent development services are ready.'
Write-Host 'Web:    http://127.0.0.1:15173/'
Write-Host 'Health: http://127.0.0.1:18080/actuator/health'
Write-Host 'Worker: http://127.0.0.1:18081/actuator/health'
Write-Host 'Logs:   .runtime\'
Write-Host 'Stop:   .\scripts\stop-dev.ps1'
