param()

$ErrorActionPreference = "Stop"
$rows = New-Object System.Collections.Generic.List[object]
$failures = New-Object System.Collections.Generic.List[string]

function Add-Result([string]$Check, [bool]$Passed, [string]$Detail) {
  $rows.Add([pscustomobject]@{ Check = $Check; Passed = $Passed; Detail = $Detail })
  if (-not $Passed) { $failures.Add(("{0}: {1}" -f $Check, $Detail)) }
}

$manifest = Get-Content "app/src/main/AndroidManifest.xml" -Raw
$service = Get-Content "app/src/main/kotlin/com/openmaps/app/service/NavigationForegroundService.kt" -Raw
$vm = Get-Content "app/src/main/kotlin/com/openmaps/app/feature/map/MapViewModel.kt" -Raw
$recovery = Get-Content "app/src/main/kotlin/com/openmaps/app/core/navigation/NavigationRecoveryStore.kt" -Raw
$shareRecovery = Get-Content "app/src/main/kotlin/com/openmaps/app/core/sharing/TripShareRecoveryStore.kt" -Raw
$tests = Get-Content "app/src/test/kotlin/com/openmaps/app/feature/map/MapViewModelTest.kt" -Raw

Add-Result "Foreground service permission" ($manifest.Contains("android.permission.FOREGROUND_SERVICE")) "FOREGROUND_SERVICE"
Add-Result "Location FGS permission" ($manifest.Contains("android.permission.FOREGROUND_SERVICE_LOCATION")) "FOREGROUND_SERVICE_LOCATION"
Add-Result "Location service type" ($manifest.Contains('android:foregroundServiceType="location"')) "service type location"
Add-Result "Foreground promotion" ($service.Contains("ServiceCompat.startForeground") -and $service.Contains("FOREGROUND_SERVICE_TYPE_LOCATION")) "ServiceCompat location foreground"
Add-Result "User-visible notification" ($service.Contains("setOngoing(true)") -and $service.Contains("setContentIntent(openApp)")) "persistent navigation notification"
Add-Result "Background nav keeps GPS" ($vm.Contains("backgroundNavigationActive") -and $vm.Contains("isAppVisible || backgroundNavigationActive")) "navigation-aware location collection"
Add-Result "Process recovery bounded" ($recovery.Contains("DEFAULT_MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L")) "6-hour local recovery TTL"
Add-Result "Navigation recovery encrypted" ($recovery.Contains("AndroidKeyStore") -and $recovery.Contains("AES/GCM/NoPadding")) "Keystore AES-GCM"
Add-Result "Recovery returns to Map" ($vm.Contains('reason = "process_recovery"') -and $vm.Contains("selectedMapMode = MapMode.Map")) "safe map-mode recovery"
Add-Result "Trip-share token encrypted" ($shareRecovery.Contains("AndroidKeyStore") -and $shareRecovery.Contains("AES/GCM/NoPadding")) "Keystore AES-GCM"
Add-Result "Trip-share recovery expires" ($shareRecovery.Contains("session.expiresAtMillis <= nowMillis")) "expiry enforced"
Add-Result "Background lifecycle regression test" ($tests.Contains("backgrounding_duringActiveNavigation_keepsLocationUpdates_untilTripStops")) "unit coverage"
Add-Result "Process recovery regression test" ($tests.Contains("processRecovery_resumesSavedNavigationAfterFreshFix")) "unit coverage"

$rows | Format-Table -AutoSize
Write-Host ""
if ($failures.Count -gt 0) {
  Write-Host "ANDROID RELEASE HARDENING ACCEPTANCE: FAILED" -ForegroundColor Red
  foreach ($failure in $failures) { Write-Host " - $failure" -ForegroundColor Red }
  exit 1
}
Write-Host "ANDROID RELEASE HARDENING ACCEPTANCE: PASSED (AUTOMATED)" -ForegroundColor Green
Write-Host "Physical-device/road validation remains mandatory and is tracked separately." -ForegroundColor Yellow
exit 0
