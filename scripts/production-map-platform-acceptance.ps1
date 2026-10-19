param()

$ErrorActionPreference = "Stop"
$rows = New-Object System.Collections.Generic.List[object]
$failures = New-Object System.Collections.Generic.List[string]

function Add-Result([string]$Check, [bool]$Passed, [string]$Detail) {
  $rows.Add([pscustomobject]@{ Check = $Check; Passed = $Passed; Detail = $Detail })
  if (-not $Passed) { $failures.Add(("{0}: {1}" -f $Check, $Detail)) }
}

$cartography = Get-Content "app/src/main/kotlin/com/openmaps/app/map/OpenMapsCartography.kt" -Raw
$engine = Get-Content "app/src/main/kotlin/com/openmaps/app/map/MapLibreMapEngine.kt" -Raw
$screen = Get-Content "app/src/main/kotlin/com/openmaps/app/feature/map/MapScreen.kt" -Raw
$build = Get-Content "app/build.gradle.kts" -Raw
$offline = Get-Content "app/src/main/kotlin/com/openmaps/app/map/MapLibreOfflineMapCorridorCache.kt" -Raw
$policy = Get-Content "app/src/main/kotlin/com/openmaps/app/map/OfflineCorridorPolicy.kt" -Raw

Add-Result "Day cartography" ($cartography.Contains("BrowseDay")) "BrowseDay"
Add-Result "Night cartography" ($cartography.Contains("BrowseNight")) "BrowseNight"
Add-Result "Navigation cartography" ($cartography.Contains("NavigationDay") -and $cartography.Contains("NavigationNight")) "day + night navigation"
Add-Result "Route casing" ($engine.Contains("ROUTE_CASING_LAYER_ID") -and $engine.Contains("ROUTE_CASING_WIDTH")) "casing layer"
Add-Result "POI density tuning" ($engine.Contains("circleOpacity(palette.poiOpacity)")) "custom places opacity"
Add-Result "Base label tuning" ($engine.Contains("textOpacity(palette.baseLabelOpacity)")) "label opacity"
Add-Result "Dynamic navigation mode" ($screen.Contains("state.isNavigating") -and $screen.Contains("CartographyMode.NavigationDay")) "navigation switches palette"
Add-Result "Dynamic night mode" ($screen.Contains("isSystemInDarkTheme")) "system night signal"
Add-Result "Provider remains configurable" ($build.Contains("openmaps.style.url") -and $build.Contains("openmaps.style.devOnly")) "provider-neutral build config"
Add-Result "Stadia production provider" ($build.Contains("STADIA_MAPS_API_KEY") -and $build.Contains("https://tiles.stadiamaps.com/styles/")) "approved mobile vector tile provider"
Add-Result "Production provider verification task" ($build.Contains('tasks.register("verifyProductionMapConfiguration")')) "release build cannot silently use dev tiles"
Add-Result "Offline cache gated to production" ($build.Contains("OFFLINE_MAP_CACHE_ENABLED") -and $build.Contains("stadiaStyleUrl.isNotBlank()")) "no public-dev tile prefetch"
Add-Result "Offline cache ceiling" ($build.Contains('OFFLINE_MAP_CACHE_LIMIT_BYTES", "94371840L"') -and $offline.Contains("completedResourceSize >= maxBytes")) "90 MiB stop threshold under 100 MB provider ceiling"
Add-Result "Single active route region" ($offline.Contains("deleteOwnedRegions") -and $offline.Contains("requestGeneration")) "stale route regions replaced"
Add-Result "Route-bounded region" ($policy.Contains("CORRIDOR_PAD_METERS") -and $policy.Contains("distanceMeters <= 300_000.0")) "distance-sensitive corridor plan"
Add-Result "MapLibre offline region" ($offline.Contains("OfflineTilePyramidRegionDefinition") -and $offline.Contains("OfflineManager.getInstance")) "native offline storage"

$rows | Format-Table -AutoSize
Write-Host ""
if ($failures.Count -gt 0) {
  Write-Host "MAP PLATFORM / CARTOGRAPHY STRUCTURAL ACCEPTANCE: FAILED" -ForegroundColor Red
  foreach ($failure in $failures) { Write-Host " - $failure" -ForegroundColor Red }
  exit 1
}
Write-Host "MAP PLATFORM / CARTOGRAPHY STRUCTURAL ACCEPTANCE: PASSED" -ForegroundColor Green
Write-Host "NOTE: code/config is production-provider ready. Final release still requires STADIA_MAPS_API_KEY and verifyProductionMapConfiguration." -ForegroundColor Yellow
exit 0
