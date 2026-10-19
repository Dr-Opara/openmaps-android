param(
  [string]$Endpoint = "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1/reliability",
  [string]$PublishableKey = "sb_publishable_gF9KreSTfMY24vhsaVd_bQ_WVGEqRYH"
)

$ErrorActionPreference = "Stop"
$rows = New-Object System.Collections.Generic.List[object]
$failures = New-Object System.Collections.Generic.List[string]

function Add-Result([string]$Check, [bool]$Passed, [string]$Detail) {
  $rows.Add([pscustomobject]@{ Check = $Check; Passed = $Passed; Detail = $Detail })
  if (-not $Passed) { $failures.Add(("{0}: {1}" -f $Check, $Detail)) }
}

$unauthorized = Invoke-WebRequest -Method Post -Uri $Endpoint -SkipHttpErrorCheck -ContentType "application/json" -Body '{"event":"app_launch","outcome":"success","appVersion":"ci","appBuild":1,"deviceApi":35,"metadata":{}}'
Add-Result "Reject missing key" ($unauthorized.StatusCode -eq 401) "HTTP $($unauthorized.StatusCode)"

$clientId = "ci-reliability-" + [Guid]::NewGuid().ToString("N")
$headers = @{ apikey = $PublishableKey; "x-openmaps-client-id" = $clientId }
$accepted = Invoke-WebRequest -Method Post -Uri $Endpoint -SkipHttpErrorCheck -Headers $headers -ContentType "application/json" -Body '{"event":"app_launch","outcome":"success","appVersion":"ci","appBuild":1,"deviceApi":35,"durationMs":1,"metadata":{"source":"acceptance"}}'
Add-Result "Accept valid reliability event" ($accepted.StatusCode -eq 202) "HTTP $($accepted.StatusCode)"

$invalid = Invoke-WebRequest -Method Post -Uri $Endpoint -SkipHttpErrorCheck -Headers $headers -ContentType "application/json" -Body '{"event":"not_real","outcome":"success","appVersion":"ci","appBuild":1,"deviceApi":35,"metadata":{}}'
Add-Result "Reject unknown event" ($invalid.StatusCode -eq 400) "HTTP $($invalid.StatusCode)"

$crashSource = Get-Content "app/src/main/kotlin/com/openmaps/app/reliability/AndroidCrashRecorder.kt" -Raw
Add-Result "Crash recorder omits exception message" (-not $crashSource.Contains("throwable.message")) "no throwable.message persistence"
Add-Result "Crash recorder fingerprints stack" ($crashSource.Contains("fingerprint") -and $crashSource.Contains("sha256Hex")) "fingerprint + SHA-256"
Add-Result "Crash recorder bounds frames" ($crashSource.Contains("MAX_STACK_FRAMES")) "bounded stack frames"

$clientSource = Get-Content "app/src/main/kotlin/com/openmaps/app/reliability/BackendReliabilityReporter.kt" -Raw
Add-Result "Client denies sensitive metadata" ($clientSource.Contains("containsSensitiveName") -and $clientSource.Contains("location")) "sensitive metadata denylist"
Add-Result "Reporter is isolated" ($clientSource.Contains("SupervisorJob() + Dispatchers.IO")) "fire-and-forget IO scope"

$migration = Get-Content "supabase/migrations/20261019151000_add_mobile_reliability_observability.sql" -Raw
Add-Result "Reliability table has RLS" ($migration.Contains("enable row level security")) "RLS enabled"
Add-Result "Anon/auth denied" ($migration.Contains("revoke all on public.mobile_reliability_events from anon, authenticated")) "runtime table not directly public"
Add-Result "Operations rollup exists" ($migration.Contains("operations_health_24h")) "unified 24h health view"

$rows | Format-Table -AutoSize
Write-Host ""
if ($failures.Count -gt 0) {
  Write-Host "RELIABILITY / OBSERVABILITY ACCEPTANCE: FAILED" -ForegroundColor Red
  foreach ($failure in $failures) { Write-Host " - $failure" -ForegroundColor Red }
  exit 1
}

Write-Host "RELIABILITY / OBSERVABILITY ACCEPTANCE: PASSED" -ForegroundColor Green
exit 0
