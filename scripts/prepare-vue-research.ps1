[CmdletBinding()]
param([switch]$Refresh)

# Local-only sample provisioning through the same authenticated APIs as the UI.
# Credentials stay in memory and are never printed or written into the script.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$baseUrl = 'http://127.0.0.1:15173/api/v1'
$headers = @{ Origin = 'http://127.0.0.1:15173'; 'X-InsightOps-CSRF' = '1' }
$settings = @{}
Get-Content -LiteralPath (Join-Path $projectRoot '.env') | ForEach-Object {
    if ($_ -match '^(AUTH_BOOTSTRAP_USERNAME|AUTH_BOOTSTRAP_PASSWORD)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}
if (!$settings['AUTH_BOOTSTRAP_USERNAME'] -or !$settings['AUTH_BOOTSTRAP_PASSWORD']) {
    throw 'Local bootstrap login is not configured.'
}
$loginBody = @{ username = $settings['AUTH_BOOTSTRAP_USERNAME']; password = $settings['AUTH_BOOTSTRAP_PASSWORD'] } | ConvertTo-Json -Compress
$null = Invoke-RestMethod -Method Post -Uri "$baseUrl/auth/login" -Headers $headers -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($loginBody)) -SessionVariable sampleSession
$loginBody = $null
$settings.Clear()
try {
    function Invoke-SampleApi([string]$Method, [string]$Path, $Body = $null) {
        $arguments = @{ Method = $Method; Uri = "$baseUrl$Path"; Headers = $headers; WebSession = $sampleSession }
        if ($null -ne $Body) {
            $arguments.ContentType = 'application/json'
            $arguments.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress))
        }
        (Invoke-RestMethod @arguments).data
    }
    $vue = @(Invoke-SampleApi 'Get' '/admin/projects') | Where-Object { $_.repositoryOwner -eq 'vuejs' -and $_.repositoryName -eq 'core' } | Select-Object -First 1
    if (!$vue) {
        $vue = Invoke-SampleApi 'Post' '/admin/projects' @{ repositoryOwner = 'vuejs'; repositoryName = 'core'; priority = 2; syncIntervalHours = 24; chatAliases = @('Vue', 'Vue.js') }
    }
    if (!$vue.enabled) { throw 'The existing Vue project is disabled. Enable it in project administration before continuing.' }
    $null = Invoke-SampleApi 'Patch' "/projects/$($vue.projectId)/watch" @{ enabled = $true }
    $materials = @(
        @{ name = 'Vue 3.4 official release overview'; sourceType = 'OFFICIAL_RELEASE_NOTES'; url = 'https://blog.vuejs.org/posts/vue-3-4'; prefix = '/posts/vue-3-4' },
        @{ name = 'Vue 3.5 official release overview'; sourceType = 'OFFICIAL_RELEASE_NOTES'; url = 'https://blog.vuejs.org/posts/vue-3-5'; prefix = '/posts/vue-3-5' },
        @{ name = 'Vue Props guide (current documentation)'; sourceType = 'OFFICIAL_DOCUMENTATION'; url = 'https://vuejs.org/guide/components/props.html'; prefix = '/guide/components/props.html' },
        @{ name = 'Vue 3.5.0 CHANGELOG (tagged release section)'; sourceType = 'OFFICIAL_RELEASE_NOTES'; url = 'https://raw.githubusercontent.com/vuejs/core/v3.5.0/CHANGELOG.md'; prefix = '/vuejs/core/v3.5.0/CHANGELOG.md' },
        @{ name = 'Vue 3.5.1 CHANGELOG (tagged release section)'; sourceType = 'OFFICIAL_RELEASE_NOTES'; url = 'https://raw.githubusercontent.com/vuejs/core/v3.5.1/CHANGELOG.md'; prefix = '/vuejs/core/v3.5.1/CHANGELOG.md' }
    )
    $existing = @(Invoke-SampleApi 'Get' '/admin/knowledge/sources')
    foreach ($material in $materials) {
        $source = $existing | Where-Object { $_.projectId -eq $vue.projectId -and $_.rootUrl -eq $material.url } | Select-Object -First 1
        if (!$source) {
            $source = Invoke-SampleApi 'Post' '/admin/knowledge/sources' @{
                projectId = $vue.projectId; name = $material.name; sourceType = $material.sourceType
                rootUrl = $material.url; discoveryUrl = $material.url; allowedPathPrefix = $material.prefix; syncIntervalHours = 168
            }
        } elseif ($Refresh -and $source.enabled -and $source.status -ne 'RUNNING') {
            $null = Invoke-SampleApi 'Post' "/admin/knowledge/sources/$($source.sourceId)/sync"
        }
    }
    $sources = @(Invoke-SampleApi 'Get' '/admin/knowledge/sources') | Where-Object { $_.projectId -eq $vue.projectId }
    $embeddings = Invoke-SampleApi 'Get' '/admin/knowledge/embeddings'
    [pscustomobject]@{
        projectId = $vue.projectId
        repository = 'vuejs/core'
        sources = @($sources | Select-Object sourceId, name, status, documentCount, chunkCount, lastSyncAt, lastError)
        embeddings = @($embeddings.sources | Where-Object { $_.sourceId -in $sources.sourceId } | Select-Object sourceName, total, succeeded, pending, running, retryWait, failed)
    } | ConvertTo-Json -Depth 5
} finally {
    $null = Invoke-RestMethod -Method Post -Uri "$baseUrl/auth/logout" -Headers $headers -WebSession $sampleSession
}
