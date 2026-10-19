param(
    [string]$BaseUrl = "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1",
    [string]$PublishableKey = "sb_publishable_gF9KreSTfMY24vhsaVd_bQ_WVGEqRYH"
)

$ErrorActionPreference = "Stop"

function Invoke-OpenMapsPlaces {
    param(
        [hashtable]$Center,
        [string[]]$Categories,
        [double]$RadiusMeters = 3000,
        [string]$LocaleTag = "en-US"
    )
    $started = Get-Date
    $body = @{
        center = $Center
        radiusMeters = $RadiusMeters
        categories = $Categories
        localeTag = $LocaleTag
    } | ConvertTo-Json -Depth 20
    $response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/places" -Headers @{ apikey = $PublishableKey } -ContentType "application/json" -Body $body
    [pscustomobject]@{ Response = $response; LatencyMs = [int]((Get-Date) - $started).TotalMilliseconds }
}

$cases = @(
    @{ Name = "Houston"; LocaleTag = "en-US"; Center = @{ latitude = 29.7604; longitude = -95.3698 } },
    @{ Name = "London"; LocaleTag = "en-GB"; Center = @{ latitude = 51.5074; longitude = -0.1278 } },
    @{ Name = "Paris"; LocaleTag = "fr-FR"; Center = @{ latitude = 48.8566; longitude = 2.3522 } },
    @{ Name = "Lagos"; LocaleTag = "en-NG"; Center = @{ latitude = 6.5244; longitude = 3.3792 } },
    @{ Name = "Tokyo"; LocaleTag = "ja-JP"; Center = @{ latitude = 35.6762; longitude = 139.6503 } },
    @{ Name = "Sydney"; LocaleTag = "en-AU"; Center = @{ latitude = -33.8688; longitude = 151.2093 } }
)

$categories = @("Restaurant","Gas","Parking","EvCharging","Medical","Hotel","Education")
$failures = New-Object System.Collections.Generic.List[string]
$rows = New-Object System.Collections.Generic.List[object]

foreach ($case in $cases) {
    try {
        $call = Invoke-OpenMapsPlaces -Center $case.Center -Categories $categories -LocaleTag $case.LocaleTag
        $r = $call.Response
        $places = @($r.places)
        $providers = @($r.providers)
        $distinctCategories = @($places | ForEach-Object { $_.category } | Sort-Object -Unique)
        $capabilityOk = [string]$r.capability -eq "available"
        $hereOk = $providers -contains "here"
        $resultOk = $places.Count -gt 0
        $categoryOk = $distinctCategories.Count -ge 2
        $latencyOk = $call.LatencyMs -le 12000
        if (-not $capabilityOk) { $failures.Add("$($case.Name): capability was not available") }
        if (-not $hereOk) { $failures.Add("$($case.Name): HERE commercial places provider did not participate") }
        if (-not $resultOk) { $failures.Add("$($case.Name): no places returned") }
        if (-not $categoryOk) { $failures.Add("$($case.Name): fewer than two requested categories were represented") }
        if (-not $latencyOk) { $failures.Add("$($case.Name): latency $($call.LatencyMs) ms exceeded 12000 ms") }
        $rows.Add([pscustomobject]@{
            Case = $case.Name
            Locale = $case.LocaleTag
            Provider = [string]$r.provider
            Providers = ($providers -join ",")
            Places = $places.Count
            Categories = $distinctCategories.Count
            Education = @($places | Where-Object { $_.category -eq "Education" }).Count
            LatencyMs = $call.LatencyMs
            Passed = $capabilityOk -and $hereOk -and $resultOk -and $categoryOk -and $latencyOk
        })
    } catch {
        $failures.Add("$($case.Name): places request failed - $($_.Exception.Message)")
        $rows.Add([pscustomobject]@{ Case = $case.Name; Locale = $case.LocaleTag; Provider = "-"; Providers = "-"; Places = 0; Categories = 0; Education = 0; LatencyMs = 0; Passed = $false })
    }
}

$rows | Format-Table -AutoSize
Write-Host ""
if ($failures.Count -gt 0) {
    Write-Host "GLOBAL PLACES ACCEPTANCE: FAILED" -ForegroundColor Red
    foreach ($failure in $failures) { Write-Host " - $failure" -ForegroundColor Red }
    exit 1
}
Write-Host "GLOBAL PLACES ACCEPTANCE: PASSED" -ForegroundColor Green
Write-Host "HERE is the commercial primary; OSM participates opportunistically and remains the Android fallback when the gateway is unavailable." -ForegroundColor DarkGray
exit 0