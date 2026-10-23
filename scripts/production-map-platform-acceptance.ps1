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
$ambient = Get-Content "app/src/main/kotlin/com/openmaps/app/map/AmbientMapLightPolicy.kt" -Raw
$nightStyle = Get-Content "app/src/main/kotlin/com/openmaps/app/map/OpenMapsNightStyle.kt" -Raw
$appShell = Get-Content "app/src/main/kotlin/com/openmaps/app/OpenMapsApp.kt" -Raw
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
# Night mode was originally controlled only by the system theme inside MapScreen.
# It now uses the ambient light sensor with system-theme fallback, user overrides and
# hysteresis. Check the actual call chain rather than one obsolete file-local symbol.
Add-Result "Dynamic night mode" (
  $screen.Contains("rememberAmbientMapNight") -and
  $screen.Contains("MapAppearanceMode.Auto") -and
  $screen.Contains("CartographyMode.BrowseNight") -and
  $screen.Contains("CartographyMode.NavigationNight") -and
  $ambient.Contains("Sensor.TYPE_LIGHT") -and
  $ambient.Contains("isSystemInDarkTheme") -and
  $appShell.Contains("appearanceMode = mapAppearanceMode") -and
  $appShell.Contains("onMapAppearanceChanged")
) "ambient sensor + system fallback + manual override across browse/navigation"
Add-Result "Night mode anti-flicker" (
  $ambient.Contains("lux <= 12f") -and
  $ambient.Contains("lux >= 85f") -and
  $ambient.Contains("AmbientMapLightPolicy.SETTLE_MILLIS") -and
  $ambient.Contains("delay(AmbientMapLightPolicy.SETTLE_MILLIS)")
) "threshold hysteresis with timed settling"
Add-Result "Night vector style provider" (
  $engine.Contains("OpenMapsNightStyle.resolve") -and
  $nightStyle.Contains("alidade_smooth_dark.json") -and
  $nightStyle.Contains("if (!isSupported(source)) return source")
) "official Stadia night style with other providers left unchanged"
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
