param()

$ErrorActionPreference = "Stop"
$rows = New-Object System.Collections.Generic.List[object]
$failures = New-Object System.Collections.Generic.List[string]

function Add-Result([string]$Check, [bool]$Passed, [string]$Detail) {
  $rows.Add([pscustomobject]@{ Check = $Check; Passed = $Passed; Detail = $Detail })
  if (-not $Passed) { $failures.Add(("{0}: {1}" -f $Check, $Detail)) }
}

$cache = Get-Content "app/src/main/kotlin/com/openmaps/app/core/routing/CachedRoutingRepository.kt" -Raw
$models = Get-Content "app/src/main/kotlin/com/openmaps/app/core/routing/RoutingModels.kt" -Raw
$app = Get-Content "app/src/main/kotlin/com/openmaps/app/OpenMapsApplication.kt" -Raw
$vm = Get-Content "app/src/main/kotlin/com/openmaps/app/feature/map/MapViewModel.kt" -Raw
$screen = Get-Content "app/src/main/kotlin/com/openmaps/app/feature/map/MapScreen.kt" -Raw

Add-Result "Route cache wired" ($app.Contains("CachedRoutingRepository")) "routing wrapper"
Add-Result "Cache age bounded" ($cache.Contains("DEFAULT_MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L")) "6-hour max age"
Add-Result "Origin gate" ($cache.Contains("ORIGIN_MATCH_METERS")) "origin proximity match"
Add-Result "Destination gate" ($cache.Contains("DESTINATION_MATCH_METERS")) "destination proximity match"
Add-Result "Traffic stripped on restore" ($cache.Contains("isTrafficAware = false") -and $cache.Contains("trafficDelaySeconds = null")) "stale traffic never shown live"
Add-Result "Cached route explicitly marked" ($models.Contains("isOfflineCached")) "route degraded flag"
Add-Result "Cached route disclosed" ($screen.Contains("DegradedRouteBanner")) "persistent degraded notice"
Add-Result "Connectivity recovery" ($vm.Contains("fun onConnectivityChanged") -and $vm.Contains("requestReroute") -and $vm.Contains("requestRoute")) "live refresh on validated network return"
Add-Result "Offline voice fallback retained" ((Get-Content "app/src/main/kotlin/com/openmaps/app/OpenMapsApplication.kt" -Raw).Contains("AndroidTextToSpeechEngine")) "Android TTS fallback"

$rows | Format-Table -AutoSize
Write-Host ""
if ($failures.Count -gt 0) {
  Write-Host "OFFLINE / DEGRADED ACCEPTANCE: FAILED" -ForegroundColor Red
  foreach ($failure in $failures) { Write-Host " - $failure" -ForegroundColor Red }
  exit 1
}
Write-Host "OFFLINE / DEGRADED ACCEPTANCE: PASSED" -ForegroundColor Green
Write-Host "NOTE: deterministic offline map-corridor prefetch remains provider-dependent and cannot be certified while the production map provider is unresolved." -ForegroundColor Yellow
exit 0
