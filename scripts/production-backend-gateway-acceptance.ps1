param(
  [string]$Endpoint = "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1/platform",
  [string]$PublishableKey = "sb_publishable_gF9KreSTfMY24vhsaVd_bQ_WVGEqRYH"
)

$ErrorActionPreference = "Stop"
$rows = New-Object System.Collections.Generic.List[object]
$failures = New-Object System.Collections.Generic.List[string]

function Add-Result([string]$Check, [bool]$Passed, [string]$Detail) {
  $rows.Add([pscustomobject]@{ Check = $Check; Passed = $Passed; Detail = $Detail })
  if (-not $Passed) { $failures.Add(("{0}: {1}" -f $Check, $Detail)) }
}

$unauthorized = Invoke-WebRequest -Method Get -Uri $Endpoint -SkipHttpErrorCheck
Add-Result "Reject missing key" ($unauthorized.StatusCode -eq 401) "HTTP $($unauthorized.StatusCode)"

$requestId = "acceptance-" + [Guid]::NewGuid().ToString("N")
$clientId = "acceptance-client-" + [Guid]::NewGuid().ToString("N")
$response = Invoke-WebRequest -Method Get -Uri $Endpoint -SkipHttpErrorCheck -Headers @{
  apikey = $PublishableKey
  "x-openmaps-client-id" = $clientId
  "x-openmaps-request-id" = $requestId
}
Add-Result "Authorized health" ($response.StatusCode -eq 200) "HTTP $($response.StatusCode)"

$body = $null
try { $body = $response.Content | ConvertFrom-Json } catch {}
Add-Result "Service contract" (
  $null -ne $body -and
  $body.service -eq "openmaps-backend" -and
  $body.status -eq "ok" -and
  $body.apiVersion -eq "v1"
) "service/status/apiVersion"

$requiredCapabilities = @(
  "search","reverseGeocoding","routing","trafficIncidents","laneGuidance",
  "places","tripSharing","communityReports","neuralVoice"
)
$capabilitiesOk = $true
foreach ($name in $requiredCapabilities) {
  if ($null -eq $body.capabilities -or $body.capabilities.$name -ne $true) {
    $capabilitiesOk = $false
  }
}
Add-Result "Capability contract" $capabilitiesOk ($requiredCapabilities -join ", ")

$apiVersionHeader = [string]$response.Headers["x-openmaps-api-version"]
$responseRequestId = [string]$response.Headers["x-openmaps-request-id"]
Add-Result "API version header" ($apiVersionHeader -eq "v1") $apiVersionHeader
Add-Result "Request correlation" ($responseRequestId -eq $requestId) $responseRequestId

$buildFile = Get-Content "app/build.gradle.kts" -Raw
Add-Result "Unified backend base" ($buildFile.Contains("OPENMAPS_BACKEND_BASE_URL")) "OPENMAPS_BACKEND_BASE_URL"
Add-Result "Unified publishable key" ($buildFile.Contains("BACKEND_PUBLISHABLE_KEY")) "BACKEND_PUBLISHABLE_KEY"

$forbidden = @("HERE_API_KEY","MAPBOX_ACCESS_TOKEN","OPENAI_API_KEY","SUPABASE_SERVICE_ROLE_KEY")
$clientFiles = @("app/build.gradle.kts")
$clientFiles += Get-ChildItem "app/src/main" -Recurse -File | ForEach-Object { $_.FullName }
$secretHits = New-Object System.Collections.Generic.List[string]
foreach ($file in $clientFiles) {
  $text = Get-Content $file -Raw -ErrorAction SilentlyContinue
  foreach ($name in $forbidden) {
    if ($text -and $text.Contains($name)) {
      $secretHits.Add("$name in $file")
    }
  }
}
$secretDetail = if ($secretHits.Count -eq 0) { "server-only provider credentials absent" } else { $secretHits -join "; " }
Add-Result "No provider secrets in Android" ($secretHits.Count -eq 0) $secretDetail

$rows | Format-Table -AutoSize
Write-Host ""
if ($failures.Count -gt 0) {
  Write-Host "PRODUCTION BACKEND GATEWAY ACCEPTANCE: FAILED" -ForegroundColor Red
  foreach ($failure in $failures) { Write-Host " - $failure" -ForegroundColor Red }
  exit 1
}

Write-Host "PRODUCTION BACKEND GATEWAY ACCEPTANCE: PASSED" -ForegroundColor Green
exit 0
