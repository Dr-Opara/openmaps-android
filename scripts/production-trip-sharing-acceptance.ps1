param(
    [string]$Endpoint = "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1/trip-share",
    [string]$PublishableKey = "sb_publishable_gF9KreSTfMY24vhsaVd_bQ_WVGEqRYH"
)

$ErrorActionPreference = "Stop"

function Invoke-TripSharePost {
    param([hashtable]$Body)
    $started = Get-Date
    $response = Invoke-RestMethod -Method Post -Uri $Endpoint -Headers @{ apikey = $PublishableKey } -ContentType "application/json" -Body ($Body | ConvertTo-Json -Depth 20)
    [pscustomobject]@{ Response = $response; LatencyMs = [int]((Get-Date) - $started).TotalMilliseconds }
}

$failures = New-Object System.Collections.Generic.List[string]
$rows = New-Object System.Collections.Generic.List[object]

$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$create = Invoke-TripSharePost -Body @{
    action = "create"
    destination = @{ name = "OpenMaps Acceptance Destination"; latitude = 29.7604; longitude = -95.3698 }
    currentLocation = @{ latitude = 29.7858; longitude = -95.8245; headingDegrees = 90.0; speedMetersPerSecond = 12.0 }
    expiresAtMillis = $now + (30 * 60 * 1000)
    remainingDistanceMeters = 12000.0
    remainingDurationSeconds = 1200.0
}
$session = $create.Response
$createOk = -not [string]::IsNullOrWhiteSpace([string]$session.sessionId) -and -not [string]::IsNullOrWhiteSpace([string]$session.ownerToken) -and -not [string]::IsNullOrWhiteSpace([string]$session.url)
if (-not $createOk) { $failures.Add("Create: session id, owner token, or share URL missing") }
$rows.Add([pscustomobject]@{ Step = "Create"; Status = if ($createOk) { "active" } else { "failed" }; LatencyMs = $create.LatencyMs; Passed = $createOk })

$initial = Invoke-RestMethod -Method Get -Uri ($session.url + "&format=json")
$initialOk = $initial.status -eq "active" -and [double]$initial.remainingDurationSeconds -eq 1200.0
if (-not $initialOk) { $failures.Add("Viewer: initial shared state incorrect") }
$rows.Add([pscustomobject]@{ Step = "Viewer initial"; Status = [string]$initial.status; LatencyMs = "-"; Passed = $initialOk })

$update = Invoke-TripSharePost -Body @{
    action = "update"
    sessionId = [string]$session.sessionId
    ownerToken = [string]$session.ownerToken
    currentLocation = @{ latitude = 29.7700; longitude = -95.5000; headingDegrees = 95.0; speedMetersPerSecond = 13.0 }
    remainingDistanceMeters = 6400.0
    remainingDurationSeconds = 600.0
    arrived = $false
}
$updated = Invoke-RestMethod -Method Get -Uri ($session.url + "&format=json")
$updateOk = $updated.status -eq "active" -and [double]$updated.remainingDurationSeconds -eq 600.0 -and [math]::Abs([double]$updated.location.latitude - 29.7700) -lt 0.00001
if (-not $updateOk) { $failures.Add("Update: viewer did not receive current location/ETA") }
$rows.Add([pscustomobject]@{ Step = "Live update"; Status = [string]$updated.status; LatencyMs = $update.LatencyMs; Passed = $updateOk })

$arrive = Invoke-TripSharePost -Body @{
    action = "update"
    sessionId = [string]$session.sessionId
    ownerToken = [string]$session.ownerToken
    currentLocation = @{ latitude = 29.7604; longitude = -95.3698; headingDegrees = 90.0; speedMetersPerSecond = 0.0 }
    remainingDistanceMeters = 0.0
    remainingDurationSeconds = 0.0
    arrived = $true
}
$arrived = Invoke-RestMethod -Method Get -Uri ($session.url + "&format=json")
$arrivalOk = $arrived.status -eq "arrived" -and $null -eq $arrived.location -and [double]$arrived.remainingDurationSeconds -eq 0.0
if (-not $arrivalOk) { $failures.Add("Arrival: sharing did not shut down location exposure") }
$rows.Add([pscustomobject]@{ Step = "Arrival shutdown"; Status = [string]$arrived.status; LatencyMs = $arrive.LatencyMs; Passed = $arrivalOk })

$create2 = Invoke-TripSharePost -Body @{
    action = "create"
    destination = @{ name = "Stop Test"; latitude = 29.7604; longitude = -95.3698 }
    currentLocation = @{ latitude = 29.7858; longitude = -95.8245 }
    expiresAtMillis = $now + (30 * 60 * 1000)
}
$s2 = $create2.Response
$stop = Invoke-TripSharePost -Body @{ action = "stop"; sessionId = [string]$s2.sessionId; ownerToken = [string]$s2.ownerToken }
$stopOk = $false
try {
    Invoke-RestMethod -Method Get -Uri ($s2.url + "&format=json") | Out-Null
} catch {
    $stopOk = $_.Exception.Message -match "410" -or $_.ErrorDetails.Message -match "sharing_ended"
}
if (-not $stopOk) { $failures.Add("Stop: public link was not revoked") }
$rows.Add([pscustomobject]@{ Step = "Manual stop"; Status = if ($stopOk) { "revoked" } else { "failed" }; LatencyMs = $stop.LatencyMs; Passed = $stopOk })

$rows | Format-Table -AutoSize
Write-Host ""
if ($failures.Count -gt 0) {
    Write-Host "LIVE TRIP SHARING ACCEPTANCE: FAILED" -ForegroundColor Red
    foreach ($failure in $failures) { Write-Host " - $failure" -ForegroundColor Red }
    exit 1
}
Write-Host "LIVE TRIP SHARING ACCEPTANCE: PASSED" -ForegroundColor Green
Write-Host "Public links use separate share tokens; owner capability tokens are required for update/stop and are never placed in the viewer URL." -ForegroundColor DarkGray
exit 0