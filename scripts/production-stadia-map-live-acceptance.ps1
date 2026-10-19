param(
  [string]$ApiKey = $env:STADIA_MAPS_API_KEY,
  [string]$StyleId = "alidade_smooth"
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($ApiKey)) {
  Write-Host "STADIA LIVE ACCEPTANCE: BLOCKED - STADIA_MAPS_API_KEY is not configured." -ForegroundColor Yellow
  exit 2
}

$rows = New-Object System.Collections.Generic.List[object]
$failures = New-Object System.Collections.Generic.List[string]

function Add-Result([string]$Check, [bool]$Passed, [string]$Detail) {
  $rows.Add([pscustomobject]@{ Check = $Check; Passed = $Passed; Detail = $Detail })
  if (-not $Passed) { $failures.Add(("{0}: {1}" -f $Check, $Detail)) }
}

$styleUrl = "https://tiles.stadiamaps.com/styles/$StyleId.json?api_key=$ApiKey"
$tileJsonUrl = "https://tiles.stadiamaps.com/data/openmaptiles.json?api_key=$ApiKey"

$style = Invoke-WebRequest -Method Get -Uri $styleUrl -SkipHttpErrorCheck
Add-Result "Style endpoint" ($style.StatusCode -eq 200) "HTTP $($style.StatusCode)"

if ($style.StatusCode -eq 200) {
  $styleJson = $style.Content | ConvertFrom-Json
  Add-Result "MapLibre style version" ($styleJson.version -eq 8) "version=$($styleJson.version)"
  Add-Result "Vector sources present" ($null -ne $styleJson.sources) "sources loaded"
}

$tileJson = Invoke-WebRequest -Method Get -Uri $tileJsonUrl -SkipHttpErrorCheck
Add-Result "TileJSON endpoint" ($tileJson.StatusCode -eq 200) "HTTP $($tileJson.StatusCode)"

if ($tileJson.StatusCode -eq 200) {
  $tileJsonBody = $tileJson.Content | ConvertFrom-Json
  Add-Result "Vector tile templates" (@($tileJsonBody.tiles).Count -gt 0) "tile template count=$(@($tileJsonBody.tiles).Count)"
}

$rows | Format-Table -AutoSize
Write-Host ""
if ($failures.Count -gt 0) {
  Write-Host "STADIA LIVE ACCEPTANCE: FAILED" -ForegroundColor Red
  foreach ($failure in $failures) { Write-Host " - $failure" -ForegroundColor Red }
  exit 1
}

Write-Host "STADIA LIVE ACCEPTANCE: PASSED" -ForegroundColor Green
exit 0
