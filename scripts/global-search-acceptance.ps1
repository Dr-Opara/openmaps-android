param(
    [string]$BaseUrl = "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1",
    [string]$PublishableKey = "sb_publishable_gF9KreSTfMY24vhsaVd_bQ_WVGEqRYH"
)

$ErrorActionPreference = "Stop"

function Invoke-OpenMapsSearch {
    param(
        [string]$Query,
        [double]$Latitude,
        [double]$Longitude,
        [string]$Locale = "en-US"
    )

    $body = @{
        query = $Query
        context = @{
            latitude = $Latitude
            longitude = $Longitude
            localeTag = $Locale
        }
    } | ConvertTo-Json -Depth 5

    $started = Get-Date
    $response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/geocode" -Headers @{ apikey = $PublishableKey } -ContentType "application/json" -Body $body
    $elapsed = [int]((Get-Date) - $started).TotalMilliseconds

    return [pscustomobject]@{
        Response = $response
        LatencyMs = $elapsed
    }
}

function Invoke-OpenMapsReverse {
    param(
        [double]$Latitude,
        [double]$Longitude,
        [string]$Locale = "en-US"
    )

    $body = @{
        latitude = $Latitude
        longitude = $Longitude
        localeTag = $Locale
    } | ConvertTo-Json -Depth 5

    return Invoke-RestMethod -Method Post -Uri "$BaseUrl/reverse-geocode" -Headers @{ apikey = $PublishableKey } -ContentType "application/json" -Body $body
}

$cases = @(
    @{
        Name = "Exact US address"
        Query = "2880 Jordan Ranch Blvd, Brookshire, TX 77423"
        Latitude = 29.7858
        Longitude = -95.8245
        ExpectedAny = @("2880", "Jordan Ranch", "Brookshire")
        MaxLatencyMs = 4000
    },
    @{
        Name = "US school POI"
        Query = "Melton Elementary School"
        Latitude = 29.7858
        Longitude = -95.8245
        ExpectedAny = @("Melton", "Elementary")
        MaxLatencyMs = 4000
    },
    @{
        Name = "France landmark"
        Query = "Eiffel Tower, Paris"
        Latitude = 48.8566
        Longitude = 2.3522
        ExpectedAny = @("Eiffel", "Tour Eiffel")
        MaxLatencyMs = 4000
    },
    @{
        Name = "UK exact address"
        Query = "10 Downing Street, London"
        Latitude = 51.5074
        Longitude = -0.1278
        ExpectedAny = @("Downing", "10")
        MaxLatencyMs = 4000
    },
    @{
        Name = "Nigeria POI"
        Query = "Lekki Conservation Centre, Lagos"
        Latitude = 6.4698
        Longitude = 3.5852
        ExpectedAny = @("Lekki", "Conservation")
        MaxLatencyMs = 4000
    }
)

$failures = New-Object System.Collections.Generic.List[string]
$rows = New-Object System.Collections.Generic.List[object]

foreach ($case in $cases) {
    try {
        $call = Invoke-OpenMapsSearch -Query $case.Query -Latitude $case.Latitude -Longitude $case.Longitude

        $results = @($call.Response.results)
        $serialized = ($results | ConvertTo-Json -Depth 8)
        $matched = $false
        foreach ($expected in $case.ExpectedAny) {
            if ($serialized -match [regex]::Escape($expected)) {
                $matched = $true
                break
            }
        }

        $providerOk = @($call.Response.providers) -contains "here"
        $latencyOk = $call.LatencyMs -le $case.MaxLatencyMs
        $resultOk = $results.Count -gt 0

        if (-not $providerOk) { $failures.Add("$($case.Name): HERE provider missing") }
        if (-not $resultOk) { $failures.Add("$($case.Name): zero results") }
        if (-not $matched) { $failures.Add("$($case.Name): expected place/address token not found") }
        if (-not $latencyOk) { $failures.Add("$($case.Name): latency $($call.LatencyMs) ms exceeded $($case.MaxLatencyMs) ms") }

        $rows.Add([pscustomobject]@{
            Case = $case.Name
            QueryType = $call.Response.queryType
            Providers = (@($call.Response.providers) -join ",")
            Results = $results.Count
            LatencyMs = $call.LatencyMs
            QualityMatch = $matched
            Passed = $providerOk -and $resultOk -and $matched -and $latencyOk
        })
    }
    catch {
        $failures.Add("$($case.Name): request failed - $($_.Exception.Message)")
        $rows.Add([pscustomobject]@{
            Case = $case.Name
            QueryType = "-"
            Providers = "-"
            Results = 0
            LatencyMs = 0
            QualityMatch = $false
            Passed = $false
        })
    }
}

try {
    $reverse = Invoke-OpenMapsReverse -Latitude 51.5034 -Longitude -0.1276
    $reverseOk = $null -ne $reverse.result -and -not [string]::IsNullOrWhiteSpace($reverse.result.displayName)
    if (-not $reverseOk) {
        $failures.Add("Reverse geocoding: no human-readable location returned")
    }
    $rows.Add([pscustomobject]@{
        Case = "Reverse geocoding"
        QueryType = "reverse"
        Providers = $reverse.provider
        Results = $(if ($reverseOk) { 1 } else { 0 })
        LatencyMs = "-"
        QualityMatch = $reverseOk
        Passed = $reverseOk
    })
}
catch {
    $failures.Add("Reverse geocoding: request failed - $($_.Exception.Message)")
}

$rows | Format-Table -AutoSize

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "GLOBAL SEARCH ACCEPTANCE: FAILED" -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host " - $failure" -ForegroundColor Red
    }
    exit 1
}

Write-Host ""
Write-Host "GLOBAL SEARCH ACCEPTANCE: PASSED" -ForegroundColor Green
exit 0
