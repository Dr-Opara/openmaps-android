param(
  [string]$Endpoint = "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1/voice",
  [string]$PublishableKey = "sb_publishable_gF9KreSTfMY24vhsaVd_bQ_WVGEqRYH"
)

$ErrorActionPreference = "Stop"

$cases = @(
  @{ Language = "EnglishUs"; Text = "In half a mile, turn right onto Westheimer Road." },
  @{ Language = "EnglishUk"; Text = "In half a mile, keep left onto Oxford Street." },
  @{ Language = "Spanish"; Text = "En media milla, gire a la derecha en Gran Via." },
  @{ Language = "French"; Text = "Dans un demi-mile, tournez a droite sur Avenue des Champs-Elysees." },
  @{ Language = "NigerianPidgin"; Text = "For about half mile, turn right enter Airport Road." }
)

$rows = New-Object System.Collections.Generic.List[object]
$failures = New-Object System.Collections.Generic.List[string]

foreach ($case in $cases) {
  $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("openmaps-voice-" + [Guid]::NewGuid().ToString("N") + ".wav")
  try {
    $started = Get-Date
    $body = @{ text = $case.Text; language = $case.Language } | ConvertTo-Json
    Invoke-WebRequest -Method Post -Uri $Endpoint -Headers @{ apikey = $PublishableKey } -ContentType "application/json" -Body $body -OutFile $temp | Out-Null
    $latency = [int]((Get-Date) - $started).TotalMilliseconds
    $bytes = if (Test-Path $temp) { (Get-Item $temp).Length } else { 0 }
    $ok = $bytes -gt 1000
    if (-not $ok) { $failures.Add("$($case.Language): invalid neural audio response") }
    $rows.Add([pscustomobject]@{ Language = $case.Language; Status = 200; Bytes = $bytes; LatencyMs = $latency; Passed = $ok })
  } catch {
    $detail = $_.ErrorDetails.Message
    if ($detail -match "neural_voice_unconfigured") {
      $failures.Add("$($case.Language): OPENAI_API_KEY is not configured in Supabase")
    } else {
      $failures.Add("$($case.Language): $($_.Exception.Message)")
    }
    $rows.Add([pscustomobject]@{ Language = $case.Language; Status = "error"; Bytes = 0; LatencyMs = "-"; Passed = $false })
  } finally {
    Remove-Item $temp -Force -ErrorAction SilentlyContinue
  }
}

$rows | Format-Table -AutoSize
Write-Host ""
if ($failures.Count -gt 0) {
  Write-Host "HUMAN-QUALITY VOICE ACCEPTANCE: FAILED" -ForegroundColor Red
  foreach ($failure in $failures) { Write-Host " - $failure" -ForegroundColor Red }
  exit 1
}

Write-Host "HUMAN-QUALITY VOICE ACCEPTANCE: PASSED" -ForegroundColor Green
exit 0