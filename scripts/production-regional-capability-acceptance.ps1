param()

$ErrorActionPreference = "Stop"
$rows = New-Object System.Collections.Generic.List[object]
$failures = New-Object System.Collections.Generic.List[string]

function Add-Result([string]$Check, [bool]$Passed, [string]$Detail) {
  $rows.Add([pscustomobject]@{ Check = $Check; Passed = $Passed; Detail = $Detail })
  if (-not $Passed) { $failures.Add(("{0}: {1}" -f $Check, $Detail)) }
}

$matrix = Get-Content "config/regional-capabilities.json" -Raw | ConvertFrom-Json
$allowed = @("verified","supported","conditional","fallback","unverified","blocked","unsupported")
$expectedRegions = @("houston-us","london-gb","paris-fr","lagos-ng","tokyo-jp","sydney-au")
$requiredCapabilities = @("search","reverseGeocoding","routing","trafficEta","trafficIncidents","laneGuidance","speedLimits","places","parkingPoi","parkingOccupancy","communityReports","voice","localization","liveAr","mapPlatform","offlineRoute","offlineMap")

Add-Result "Schema version" ($matrix.schemaVersion -eq 1) "schemaVersion=$($matrix.schemaVersion)"
$actualRegions = @($matrix.regions | ForEach-Object { $_.id })
Add-Result "All launch regions present" (@($expectedRegions | Where-Object { $_ -notin $actualRegions }).Count -eq 0) ($actualRegions -join ", ")
Add-Result "Global blockers declared" (@($matrix.globalBlockers).Count -ge 4) (@($matrix.globalBlockers) -join ", ")

$statusOk = $true
$capabilityOk = $true
$evidenceOk = $true
foreach ($region in $matrix.regions) {
  foreach ($name in $requiredCapabilities) {
    $entry = $region.capabilities.$name
    if ($null -eq $entry) { $capabilityOk = $false; continue }
    if ($entry.status -notin $allowed) { $statusOk = $false }
    if ([string]::IsNullOrWhiteSpace([string]$entry.evidence)) { $evidenceOk = $false }
  }
}
Add-Result "Capability schema complete" $capabilityOk "all required capabilities per region"
Add-Result "Statuses constrained" $statusOk ($allowed -join ", ")
Add-Result "Evidence required" $evidenceOk "every capability has evidence/rationale"

$allRoutingVerified = @($matrix.regions | Where-Object { $_.capabilities.routing.status -ne "verified" }).Count -eq 0
$allPlacesVerified = @($matrix.regions | Where-Object { $_.capabilities.places.status -ne "verified" }).Count -eq 0
Add-Result "Routing verified in six cities" $allRoutingVerified "production routing acceptance evidence"
Add-Result "Places verified in six cities" $allPlacesVerified "production places acceptance evidence"

$noPrematureMapClaim = @($matrix.regions | Where-Object { $_.capabilities.mapPlatform.status -ne "blocked" }).Count -eq 0
$noPrematureArClaim = @($matrix.regions | Where-Object { $_.capabilities.liveAr.status -ne "blocked" }).Count -eq 0
$noPrematureOfflineMapClaim = @($matrix.regions | Where-Object { $_.capabilities.offlineMap.status -ne "blocked" }).Count -eq 0
Add-Result "Map blocker not overclaimed" $noPrematureMapClaim "production tile host unresolved"
Add-Result "AR blocker not overclaimed" $noPrematureArClaim "provisioning + field validation required"
Add-Result "Offline-map blocker not overclaimed" $noPrematureOfflineMapClaim "provider offline contract unresolved"

$tokyo = $matrix.regions | Where-Object { $_.id -eq "tokyo-jp" }
Add-Result "Tokyo localization honest" ($tokyo.capabilities.localization.status -eq "fallback") "Japanese UI not implemented"
Add-Result "Tokyo voice honest" ($tokyo.capabilities.voice.status -eq "fallback") "Japanese navigation voice not implemented"

$routingAcceptance = Get-Content "scripts/production-routing-acceptance.ps1" -Raw
$placesAcceptance = Get-Content "scripts/production-places-acceptance.ps1" -Raw
$cityEvidenceOk = $true
foreach ($city in @("Houston","London","Paris","Lagos","Tokyo","Sydney")) {
  if (-not $routingAcceptance.Contains($city) -or -not $placesAcceptance.Contains($city)) { $cityEvidenceOk = $false }
}
Add-Result "Six-city acceptance evidence exists" $cityEvidenceOk "routing + places scripts contain all launch cities"

$rows | Format-Table -AutoSize
Write-Host ""
if ($failures.Count -gt 0) {
  Write-Host "REGIONAL CAPABILITY / BETA READINESS ACCEPTANCE: FAILED" -ForegroundColor Red
  foreach ($failure in $failures) { Write-Host " - $failure" -ForegroundColor Red }
  exit 1
}
Write-Host "REGIONAL CAPABILITY MATRIX: VALID" -ForegroundColor Green
Write-Host "PUBLIC BETA: BLOCKED until the declared global blockers are cleared." -ForegroundColor Yellow
exit 0
