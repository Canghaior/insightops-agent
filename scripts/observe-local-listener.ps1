[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateRange(1, 65535)][int]$Port,
    [Parameter(Mandatory)][ValidateRange(1, 2147483647)][int]$RecordedProcessId
)
# Only report what Windows can observe now. A reused PID alone cannot identify the old process.
$ErrorActionPreference = 'Stop'
$started = [DateTimeOffset]::Now
$result = [ordered]@{
    port = $Port
    recordedPid = $RecordedProcessId
    observedFrom = $started.ToString('o')
    recordedPidPresent = $null
    listenerPids = $null
    state = 'unknown'
}
try {
    $recorded = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId=$RecordedProcessId"
    $listeners = @(Get-NetTCPConnection -State Listen | Where-Object LocalPort -eq $Port)
    $result.recordedPidPresent = $null -ne $recorded
    $result.listenerPids = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    if (!$recorded -and $listeners.Count -eq 0) {
        $result.state = 'recorded-process-and-listener-absent'
    } elseif ($recorded -and $result.listenerPids -contains $RecordedProcessId) {
        $result.state = 'recorded-pid-listening'
    } else {
        $result.state = 'identity-or-listener-inconclusive'
    }
} catch {
    $result.observationError = $_.Exception.GetType().Name
}
$result.observedUntil = [DateTimeOffset]::Now.ToString('o')
$result | ConvertTo-Json
