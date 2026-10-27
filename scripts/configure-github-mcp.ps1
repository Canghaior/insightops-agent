[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    throw 'Missing .env. Configure the local application first.'
}

Write-Host 'Configure the GitHub official MCP Issue reader (read-only).'
Write-Host 'Paste your GitHub PAT at the hidden prompt. It will only be saved in this project .env.'
$secureToken = Read-Host 'GitHub PAT' -AsSecureString
$tokenPointer = [IntPtr]::Zero
$tokenValue = $null
try {
    $tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
    $tokenValue = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer).Trim()
    if ($tokenValue -notmatch '^(github_pat_|ghp_)[A-Za-z0-9_]+$') {
        throw 'The input does not look like a GitHub PAT. Nothing was changed.'
    }
    $existing = [IO.File]::ReadAllLines($envPath)
    $updated = @($existing | Where-Object {
        $_ -notmatch '^\s*GITHUB_MCP_(TOKEN|ENABLED)\s*='
    }) + @('GITHUB_MCP_ENABLED=true', ('GITHUB_MCP_TOKEN=' + $tokenValue))
    [IO.File]::WriteAllLines($envPath, $updated, (New-Object Text.UTF8Encoding($false)))
    Write-Host 'GitHub MCP configuration saved. The token was not printed.'
    Write-Host 'Restart InsightOps Server to load the configuration.'
}
finally {
    if ($tokenPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer)
    }
    $tokenValue = $null
    $updated = $null
    $existing = $null
    if ($null -ne $secureToken) { $secureToken.Dispose() }
}
