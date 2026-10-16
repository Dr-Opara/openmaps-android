param(
    [string]$BaseUrl = "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1",
    [string]$PublishableKey = "sb_publishable_gF9KreSTfMY24vhsaVd_bQ_WVGEqRYH"
)

$ErrorActionPreference = "Stop"

function Invoke-OpenMapsRoute {
    param(
        [double]$OriginLat,
        [double]$OriginLon,
        [double]$DestinationLat,
        [double]$DestinationLon,
        [string]$Locale = "en-US"
    )

    $body = @{
        origin = @{ latitude = $OriginLat; longitude = $OriginLon }
        destination = @{ latitude = $DestinationLat; longitude = $DestinationLon }
        localeTag = $Locale
    } | ConvertTo-Json -Depth 5

    $started = Get-Date
    $response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/route" -Headers @{ apikey = $PublishableKey } -ContentType "application/json" -Body $body
    $elapsed = [int]((Get-Date) - $started).TotalMilliseconds

    [pscustomobject]@{
        Response = $response
        LatencyMs = $elapsed
    }
}

function Invoke-OpenMapsTraffic {
    param([object[]]$Geometry)

    $body = @{
        routeGeometry = @($Geometry | ForEach-Object {
            @{ latitude = [double]$_.latitude; longitude = [double]$_.longitude }
        })
        corridorMeters = 500
        localeTag = "en-US"
    } | ConvertTo-Json -Depth 8

    $started = Get-Date
    $response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/traffic-incidents" -Headers @{ apikey = $PublishableKey } -ContentType "application/json" -Body $body
    $elapsed = [int]((Get-Date) - $started).TotalMilliseconds

    [pscustomobject]@{
        Response = $response
        LatencyMs = $elapsed
    }
}

$cases = @(
    @{
        Name = "Houston regional"
        OriginLat = 29.7604; OriginLon = -95.3698
        DestinationLat = 29.7858; DestinationLon = -95.8245
        Locale = "en-US"
    },
    @{
        Name = "London"
        OriginLat = 51.5007; OriginLon = -0.1246
        DestinationLat = 51.5155; DestinationLon = -0.0922
        Locale = "en-GB"
    },
    @{
        Name = "Paris"
        OriginLat = 48.8584; OriginLon = 2.2945
        DestinationLat = 48.8738; DestinationLon = 2.2950
        Locale = "fr-FR"
    },
    @{
        Name = "Lagos"
        OriginLat = 6.5244; OriginLon = 3.3792
        DestinationLat = 6.4698; DestinationLon = 3.5852
        Locale = "en-NG"
    },
    @{
        Name = "Tokyo"
        OriginLat = 35.6812; OriginLon = 139.7671
        DestinationLat = 35.6895; DestinationLon = 139.6917
        Locale = "ja-JP"
    },
    @{
        Name = "Sydney"
        OriginLat = -33.8688; OriginLon = 151.2093
        DestinationLat = -33.8568; DestinationLon = 151.2153
        Locale = "en-AU"
    }
)

$failures = New-Object System.Collections.Generic.List[string]
$rows = New-Object System.Collections.Generic.List[object]
$firstGeometry = $null
$totalAlternatives = 0
$exitGuidanceSeen = $false

foreach ($case in $cases) {
    try {
        $call = Invoke-OpenMapsRoute -OriginLat $case.OriginLat -OriginLon $case.OriginLon -DestinationLat $case.DestinationLat -DestinationLon $case.DestinationLon -Locale $case.Locale
        $response = $call.Response
        $route = $response.route
        $geometry = @($route.geometry)
        $maneuvers = @($route.maneuvers)
        $alternatives = @($response.alternatives)
        $totalAlternatives += $alternatives.Count

        if ($null -eq $firstGeometry -and $geometry.Count -ge 2) { $firstGeometry = $geometry }

        foreach ($m in $maneuvers) {
            if (-not [string]::IsNullOrWhiteSpace([string]$m.exitSign) -or -not [string]::IsNullOrWhiteSpace([string]$m.signpost)) {
                $exitGuidanceSeen = $true
                break
            }
        }

        $providerOk = $response.provider -eq "here"
        $trafficOk = $response.trafficAware -eq $true
        $geometryOk = $geometry.Count -ge 2
        $maneuversOk = $maneuvers.Count -gt 0
        $distanceOk = [double]$route.distanceMeters -gt 0
        $durationOk = [double]$route.durationSeconds -gt 0
        $latencyOk = $call.LatencyMs -le 6000

        if (-not $providerOk) { $failures.Add("$($case.Name): HERE provider not active") }
        if (-not $trafficOk) { $failures.Add("$($case.Name): route is not traffic-aware") }
        if (-not $geometryOk) { $failures.Add("$($case.Name): route geometry missing") }
        if (-not $maneuversOk) { $failures.Add("$($case.Name): maneuver guidance missing") }
        if (-not $distanceOk) { $failures.Add("$($case.Name): invalid route distance") }
        if (-not $durationOk) { $failures.Add("$($case.Name): invalid route duration") }
        if (-not $latencyOk) { $failures.Add("$($case.Name): latency $($call.LatencyMs) ms exceeded 6000 ms") }

        $rows.Add([pscustomobject]@{
            Case = $case.Name
            Provider = $response.provider
            TrafficAware = $response.trafficAware
            GeometryPoints = $geometry.Count
            Maneuvers = $maneuvers.Count
            Alternatives = $alternatives.Count
            LatencyMs = $call.LatencyMs
            Passed = $providerOk -and $trafficOk -and $geometryOk -and $maneuversOk -and $distanceOk -and $durationOk -and $latencyOk
        })
    }
    catch {
        $failures.Add("$($case.Name): route request failed - $($_.Exception.Message)")
        $rows.Add([pscustomobject]@{
            Case = $case.Name
            Provider = "-"
            TrafficAware = $false
            GeometryPoints = 0
            Maneuvers = 0
            Alternatives = 0
            LatencyMs = 0
            Passed = $false
        })
    }
}

if ($null -eq $firstGeometry) {
    $failures.Add("Traffic incidents: no valid route geometry available for traffic test")
}
else {
    try {
        $traffic = Invoke-OpenMapsTraffic -Geometry $firstGeometry
        $trafficProviderOk = $traffic.Response.provider -eq "here"
        $trafficLatencyOk = $traffic.LatencyMs -le 6000
        $incidentCount = @($traffic.Response.incidents).Count

        if (-not $trafficProviderOk) { $failures.Add("Traffic incidents: HERE provider not active") }
        if (-not $trafficLatencyOk) { $failures.Add("Traffic incidents: latency $($traffic.LatencyMs) ms exceeded 6000 ms") }

        $rows.Add([pscustomobject]@{
            Case = "Live traffic incidents"
            Provider = $traffic.Response.provider
            TrafficAware = $true
            GeometryPoints = $firstGeometry.Count
            Maneuvers = "-"
            Alternatives = "-"
            LatencyMs = $traffic.LatencyMs
            Passed = $trafficProviderOk -and $trafficLatencyOk
        })

        Write-Host "Traffic incident count on acceptance route: $incidentCount"
    }
    catch {
        $failures.Add("Traffic incidents: request failed - $($_.Exception.Message)")
    }
}

$rows | Format-Table -AutoSize
Write-Host ""
Write-Host "Alternatives returned across suite: $totalAlternatives"
Write-Host "Provider exit/signpost guidance observed: $exitGuidanceSeen"
Write-Host "Note: alternatives and exit signs are road-dependent; zero does not fail the suite."

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "PRODUCTION ROUTING ACCEPTANCE: FAILED" -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host " - $failure" -ForegroundColor Red
    }
    exit 1
}

Write-Host ""
Write-Host "PRODUCTION ROUTING ACCEPTANCE: PASSED" -ForegroundColor Green
exit 0
