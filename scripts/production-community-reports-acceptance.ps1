param(
  [string]$Endpoint = "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1/community-reports",
  [string]$PublishableKey = "sb_publishable_gF9KreSTfMY24vhsaVd_bQ_WVGEqRYH"
)

$ErrorActionPreference = "Stop"

function Post-Report {
  param(
    [hashtable]$Body,
    [string]$ClientId = "acceptance-client-a"
  )
  $started = Get-Date
  $response = Invoke-RestMethod -Method Post -Uri $Endpoint -Headers @{
    apikey = $PublishableKey
    "X-OpenMaps-Client-Id" = $ClientId
  } -ContentType "application/json" -Body ($Body | ConvertTo-Json -Depth 30)
  [pscustomobject]@{ Response = $response; LatencyMs = [int]((Get-Date) - $started).TotalMilliseconds }
}

$failures = New-Object System.Collections.Generic.List[string]
$rows = New-Object System.Collections.Generic.List[object]

# Every CI run gets independent voter identities and a nearby-but-unique Houston-area coordinate.
# This keeps acceptance idempotent even when prior acceptance reports are still inside their TTL.
$runId = [Guid]::NewGuid().ToString("N")
$seed = [Math]::Abs([Guid]::NewGuid().GetHashCode())
$lat = 29.500 + (($seed % 350) / 1000.0)
$lon = -95.700 + (([Math]::Floor($seed / 350) % 350) / 1000.0)
$clientA = "acceptance-$runId-a"
$clientB = "acceptance-$runId-b"
$clientC = "acceptance-$runId-c"
$clientD = "acceptance-$runId-d"

$submit = Post-Report -ClientId $clientA -Body @{ action = "submit"; type = "Crash"; location = @{ latitude = $lat; longitude = $lon } }
$id = [string]$submit.Response.reportId
$submitOk = -not [string]::IsNullOrWhiteSpace($id) -and [string]$submit.Response.status -eq "active"
if (-not $submitOk) { $failures.Add("Submit did not return an active report id") }
$rows.Add([pscustomobject]@{ Step = "Submit"; Result = [string]$submit.Response.status; LatencyMs = $submit.LatencyMs; Passed = $submitOk })

$route = @(
  @{ latitude = $lat - 0.003; longitude = $lon - 0.003 },
  @{ latitude = $lat; longitude = $lon },
  @{ latitude = $lat + 0.003; longitude = $lon + 0.003 }
)
$query = Post-Report -Body @{ action = "query"; routeGeometry = $route; corridorMeters = 300 }
$match = @($query.Response.reports) | Where-Object { [string]$_.id -eq $id } | Select-Object -First 1
$queryOk = $null -ne $match -and [string]$match.type -eq "Crash"
if (-not $queryOk) { $failures.Add("Submitted report was not returned by route query") }
$rows.Add([pscustomobject]@{ Step = "Route query"; Result = if ($queryOk) { "found" } else { "missing" }; LatencyMs = $query.LatencyMs; Passed = $queryOk })

$confirm = Post-Report -ClientId $clientB -Body @{ action = "vote"; reportId = $id; vote = "confirm" }
$query2 = Post-Report -Body @{ action = "query"; routeGeometry = $route; corridorMeters = 300 }
$confirmed = @($query2.Response.reports) | Where-Object { [string]$_.id -eq $id } | Select-Object -First 1
$confirmOk = [bool]$confirm.Response.ok -and $null -ne $confirmed -and [int]$confirmed.confirmations -ge 1 -and [double]$confirmed.confidence -gt 0.35
if (-not $confirmOk) { $failures.Add("Confirmation did not raise report confidence") }
$rows.Add([pscustomobject]@{ Step = "Confirm"; Result = if ($confirmOk) { "confidence raised" } else { "failed" }; LatencyMs = $confirm.LatencyMs; Passed = $confirmOk })

$cluster = Post-Report -ClientId $clientC -Body @{ action = "submit"; type = "Crash"; location = @{ latitude = $lat + 0.00002; longitude = $lon - 0.00002 } }
$clusterOk = [bool]$cluster.Response.clustered -and [string]$cluster.Response.reportId -eq $id
if (-not $clusterOk) { $failures.Add("Nearby duplicate report was not clustered") }
$rows.Add([pscustomobject]@{ Step = "Cluster duplicate"; Result = if ($clusterOk) { "clustered" } else { "duplicate" }; LatencyMs = $cluster.LatencyMs; Passed = $clusterOk })

$dismiss = Post-Report -ClientId $clientD -Body @{ action = "vote"; reportId = $id; vote = "dismiss" }
$query3 = Post-Report -Body @{ action = "query"; routeGeometry = $route; corridorMeters = 300 }
$dismissed = @($query3.Response.reports) | Where-Object { [string]$_.id -eq $id } | Select-Object -First 1
$dismissOk = [bool]$dismiss.Response.ok -and $null -ne $dismissed -and [int]$dismissed.dismissals -ge 1
if (-not $dismissOk) { $failures.Add("Dismiss vote was not recorded") }
$rows.Add([pscustomobject]@{ Step = "Dismiss"; Result = if ($dismissOk) { "recorded" } else { "failed" }; LatencyMs = $dismiss.LatencyMs; Passed = $dismissOk })

$rows | Format-Table -AutoSize
Write-Host ""
if ($failures.Count -gt 0) {
  Write-Host "COMMUNITY REPORTS ACCEPTANCE: FAILED" -ForegroundColor Red
  foreach ($failure in $failures) { Write-Host " - $failure" -ForegroundColor Red }
  exit 1
}
Write-Host "COMMUNITY REPORTS ACCEPTANCE: PASSED" -ForegroundColor Green
exit 0