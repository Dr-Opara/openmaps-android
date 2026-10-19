param()

$ErrorActionPreference = "Stop"
$rows = New-Object System.Collections.Generic.List[object]
$failures = New-Object System.Collections.Generic.List[string]

function Add-Result([string]$Check, [bool]$Passed, [string]$Detail) {
  $rows.Add([pscustomobject]@{ Check = $Check; Passed = $Passed; Detail = $Detail })
  if (-not $Passed) { $failures.Add(("{0}: {1}" -f $Check, $Detail)) }
}

$manifest = Get-Content "app/src/main/AndroidManifest.xml" -Raw
$network = Get-Content "app/src/main/res/xml/network_security_config.xml" -Raw
$safety = Get-Content "app/src/main/kotlin/com/openmaps/app/feature/map/NavigationControls.kt" -Raw
$settings = Get-Content "app/src/main/kotlin/com/openmaps/app/feature/settings/SettingsScreen.kt" -Raw
$strings = Get-Content "app/src/main/res/values/strings.xml" -Raw
$retention = Get-Content "supabase/migrations/20261019162500_privacy_retention_policies.sql" -Raw
$build = Get-Content "app/build.gradle.kts" -Raw
$mainSource = (Get-ChildItem "app/src/main" -Recurse -File | ForEach-Object { Get-Content $_ -Raw -ErrorAction SilentlyContinue }) -join "`n"

Add-Result "Cleartext disabled" ($manifest.Contains('android:usesCleartextTraffic="false"') -and $network.Contains('cleartextTrafficPermitted="false"')) "TLS-only Android network policy"
Add-Result "Network config declared" ($manifest.Contains('android:networkSecurityConfig="@xml/network_security_config"')) "network security config"
Add-Result "ARCore disclosure visible" ($strings.Contains("live_arcore_disclosure") -and $safety.Contains("live_arcore_disclosure")) "ARCore disclosure in activation dialog"
Add-Result "Geospatial sensor disclosure" ($strings.Contains("live_geospatial_disclosure") -and $safety.Contains("live_geospatial_disclosure")) "camera/location disclosure"
Add-Result "ARCore learn-more URL" ($safety.Contains("https://support.google.com/ar?p=how-google-play-services-for-ar-handles-your-data")) "Google required learn-more URL"
Add-Result "Privacy remains accessible" ($settings.Contains("settings_section_privacy") -and $settings.Contains("settings_privacy_telemetry")) "Settings privacy section"
Add-Result "Telemetry retention bounded" ($retention.Contains("interval '30 days'")) "30-day telemetry retention"
Add-Result "Trip sessions short-retention" ($retention.Contains("trip_share_sessions") -and $retention.Contains("interval '24 hours'")) "completed/expired trip sessions aged out"
Add-Result "Rate limit buckets bounded" ($retention.Contains("interval '2 days'")) "2-day rate bucket retention"
Add-Result "Daily retention scheduled" ($retention.Contains("openmaps-daily-retention-cleanup") -and $retention.Contains("cron.schedule")) "daily pg_cron job"

$forbidden = @("SUPABASE_SERVICE_ROLE_KEY","HERE_API_KEY","OPENAI_API_KEY","MAPBOX_ACCESS_TOKEN")
$secretWiring = @()
foreach ($name in $forbidden) {
  if ($mainSource.Contains($name) -or $build.Contains("BuildConfig.$name") -or $build.Contains('buildConfigField("String", "' + $name + '"')) {
    $secretWiring += $name
  }
}
$secretDetail = if ($secretWiring.Count -eq 0) { "none" } else { $secretWiring -join ", " }
Add-Result "No server secrets in Android" ($secretWiring.Count -eq 0) $secretDetail

$rows | Format-Table -AutoSize
Write-Host ""
if ($failures.Count -gt 0) {
  Write-Host "PRIVACY / SECURITY ACCEPTANCE: FAILED" -ForegroundColor Red
  foreach ($failure in $failures) { Write-Host " - $failure" -ForegroundColor Red }
  exit 1
}
Write-Host "PRIVACY / SECURITY ACCEPTANCE: PASSED" -ForegroundColor Green
exit 0
