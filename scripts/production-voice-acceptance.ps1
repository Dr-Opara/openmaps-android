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
  @{ Language = "Arabic"; Text = "بعد 800 متر، انعطف يمينًا إلى شارع المطار." },
  @{ Language = "Hebrew"; Text = "בעוד 800 מטר, פנו ימינה אל דרך נמיר." },
  @{ Language = "NigerianPidgin"; Text = "For about half mile, turn right enter Airport Road." },
  @{ Language = "EnglishUs"; Text = "In 800 meters, turn right onto Airport Road." }
)

$rows = New-Object System.Collections.Generic.List[object]
$failures = New-Object System.Collections.Generic.List[string]

function Invoke-VoiceWithRetry {
  param([string]$Body, [string]$OutFile)

  for ($attempt = 1; $attempt -le 3; $attempt++) {
    try {
      Remove-Item $OutFile -Force -ErrorAction SilentlyContinue
      Invoke-WebRequest -Method Post -Uri $Endpoint -Headers @{ apikey = $PublishableKey } -ContentType "application/json" -Body $Body -OutFile $OutFile | Out-Null
      return
    } catch {
      $status = 0
      try { $status = [int]$_.Exception.Response.StatusCode } catch { }
      $transient = $status -eq 429 -or $status -ge 500 -or $status -eq 0
      if (-not $transient -or $attempt -ge 3) { throw }
      Start-Sleep -Seconds (2 * $attempt)
    }
  }
}

foreach ($case in $cases) {
  $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("openmaps-voice-" + [Guid]::NewGuid().ToString("N") + ".wav")
  try {
    $started = Get-Date
    $body = @{ text = $case.Text; language = $case.Language } | ConvertTo-Json
    Invoke-VoiceWithRetry -Body $body -OutFile $temp
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