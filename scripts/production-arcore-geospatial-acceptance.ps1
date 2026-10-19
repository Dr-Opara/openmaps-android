param()

$ErrorActionPreference = "Stop"
$rows = New-Object System.Collections.Generic.List[object]
$failures = New-Object System.Collections.Generic.List[string]

function Add-Result([string]$Check, [bool]$Passed, [string]$Detail) {
  $rows.Add([pscustomobject]@{ Check = $Check; Passed = $Passed; Detail = $Detail })
  if (-not $Passed) { $failures.Add(("{0}: {1}" -f $Check, $Detail)) }
}

$manifest = Get-Content "app/src/main/AndroidManifest.xml" -Raw
$build = Get-Content "app/build.gradle.kts" -Raw
$ar = Get-Content "app/src/main/kotlin/com/openmaps/app/core/spatial/arcore/ArCoreSpatialLocalization.kt" -Raw
$safety = Get-Content "app/src/main/kotlin/com/openmaps/app/feature/map/NavigationControls.kt" -Raw
$strings = Get-Content "app/src/main/res/values/strings.xml" -Raw
$mainSource = (Get-ChildItem "app/src/main" -Recurse -File | ForEach-Object { Get-Content $_ -Raw -ErrorAction SilentlyContinue }) -join "`n"

Add-Result "AR hardware optional" ($manifest.Contains('android.hardware.camera.ar') -and $manifest.Contains('android:required="false"')) "non-AR devices retain CameraX fallback"
Add-Result "ARCore manifest key seam" ($manifest.Contains('com.google.android.ar.API_KEY') -and $manifest.Contains('${ARCORE_API_KEY}')) "manifest placeholder"
Add-Result "External ARCore key resolution" (
  $build.Contains('providers.gradleProperty("ARCORE_API_KEY")') -and
  $build.Contains('providers.environmentVariable("ARCORE_API_KEY")') -and
  $build.Contains('localProperties.getProperty("ARCORE_API_KEY")')
) "Gradle property -> environment -> local.properties"
Add-Result "Production ARCore verification task" ($build.Contains('tasks.register("verifyProductionArCoreConfiguration")')) "explicit pre-release credential gate"
Add-Result "ARCore configuration state exposed" ($build.Contains('"ARCORE_GEOSPATIAL_CONFIGURED"')) "non-secret boolean BuildConfig state"
Add-Result "Geospatial mode enabled" ($ar.Contains("Config.GeospatialMode.ENABLED")) "ARCore Earth mode"
Add-Result "Authorization failure explicit" ($ar.Contains("Earth.EarthState.ERROR_NOT_AUTHORIZED")) "bad/missing production authorization cannot masquerade as success"
Add-Result "Camera fallback preserved" ($ar.Contains("Geospatial mode not supported on this device")) "unsupported devices degrade safely"
Add-Result "ARCore disclosure visible" ($strings.Contains("live_arcore_disclosure") -and $safety.Contains("live_arcore_disclosure")) "activation disclosure"
Add-Result "Geospatial sensor disclosure" ($strings.Contains("live_geospatial_disclosure") -and $safety.Contains("live_geospatial_disclosure")) "camera/location processing disclosure"
Add-Result "ARCore privacy learn-more" ($safety.Contains("https://support.google.com/ar?p=how-google-play-services-for-ar-handles-your-data")) "required Learn More destination"

$embeddedKeyPattern = [regex]::Match(($mainSource + "`n" + $build), 'AIza[0-9A-Za-z_-]{20,}')
Add-Result "No committed Google API key" (-not $embeddedKeyPattern.Success) "ARCore credential remains external"

$rows | Format-Table -AutoSize
Write-Host ""
if ($failures.Count -gt 0) {
  Write-Host "ARCORE GEOSPATIAL STRUCTURAL ACCEPTANCE: FAILED" -ForegroundColor Red
  foreach ($failure in $failures) { Write-Host " - $failure" -ForegroundColor Red }
  exit 1
}
Write-Host "ARCORE GEOSPATIAL STRUCTURAL ACCEPTANCE: PASSED" -ForegroundColor Green
Write-Host "NOTE: live Earth authorization still requires ARCORE_API_KEY plus a physical ARCore-capable device." -ForegroundColor Yellow
exit 0
