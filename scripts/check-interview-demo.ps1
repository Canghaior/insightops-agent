[CmdletBinding()]
param()

# Read-only local readiness check. Does not read credentials or call a paid model.
$ErrorActionPreference = 'Stop'
$checks = @()
foreach ($service in @(
    @{ Name = 'Web'; Url = 'http://127.0.0.1:15173/'; Health = $false },
    @{ Name = 'Server'; Url = 'http://127.0.0.1:18080/actuator/health'; Health = $true },
    @{ Name = 'Worker'; Url = 'http://127.0.0.1:18081/actuator/health'; Health = $true }
)) {
    try {
        if ($service.Health) {
            $health = Invoke-RestMethod -Uri $service.Url -TimeoutSec 5
            $ready = $health.status -eq 'UP'
        } else {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $service.Url -TimeoutSec 5
            $ready = $response.StatusCode -eq 200
        }
        $checks += [pscustomobject]@{ Service = $service.Name; Ready = $ready; Detail = $service.Url }
    } catch {
        $checks += [pscustomobject]@{ Service = $service.Name; Ready = $false; Detail = 'Unreachable; check local services and .runtime logs.' }
    }
}
try {
    $models = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/tags' -TimeoutSec 5
    $ready = @($models.models | Where-Object { $_.name -match '^bge-m3(:|$)' }).Count -gt 0
    $checks += [pscustomobject]@{ Service = 'Ollama bge-m3'; Ready = $ready; Detail = 'Local embedding model installed (no inference requested).' }
} catch {
    $checks += [pscustomobject]@{ Service = 'Ollama bge-m3'; Ready = $false; Detail = 'Start Ollama; ensure bge-m3 is installed.' }
}
$checks | Format-Table -AutoSize
Write-Host 'This checks local readiness only. Live research also needs working DeepSeek/GitHub access.'
Write-Host 'Demo: http://127.0.0.1:15173/upgrade-assessment'
if (@($checks | Where-Object { !$_.Ready }).Count -gt 0) { exit 1 }
