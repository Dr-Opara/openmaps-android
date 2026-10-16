param(
    [string]$BaseUrl = "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1",
    [string]$PublishableKey = "sb_publishable_gF9KreSTfMY24vhsaVd_bQ_WVGEqRYH"
)

$ErrorActionPreference = "Stop"

function Invoke-OpenMapsFunction {
    param([string]$Name, [hashtable]$Body)
    $started = Get-Date
    $response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/$Name" -Headers @{ apikey = $PublishableKey } -ContentType "application/json" -Body ($Body | ConvertTo-Json -Depth 20)
    [pscustomobject]@{
        Response = $response
        LatencyMs = [int]((Get-Date) - $started).TotalMilliseconds
    }
}


function Get-OpenMapsHttpErrorDetail {
    param($ErrorRecord)

    $parts = New-Object System.Collections.Generic.List[string]
    if ($null -ne $ErrorRecord.ErrorDetails -and -not [string]::IsNullOrWhiteSpace($ErrorRecord.ErrorDetails.Message)) {
        $parts.Add($ErrorRecord.ErrorDetails.Message.Trim())
    }

    $response = $ErrorRecord.Exception.Response
    if ($null -ne $response) {
        try {
            if ($response.Headers.Contains("Retry-After")) {
                $parts.Add("Retry-After=" + ($response.Headers.GetValues("Retry-After") -join ","))
            }
        } catch { }
    }

    if ($parts.Count -eq 0) {
        return $ErrorRecord.Exception.Message
    }
    return ($parts -join " | ")
}

function Invoke-LaneGuidanceWithRetry {
    param(
        [hashtable]$Body,
        [int]$MaxAttempts = 6
    )

    $delays = @(30, 60, 120, 180, 210)
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        try {
            return Invoke-OpenMapsFunction -Name "lane-guidance" -Body $Body
        } catch {
            $message = $_.Exception.Message
            $detail = Get-OpenMapsHttpErrorDetail $_
            $is429 = $message -match "429" -or $message -match "Too Many Requests" -or $detail -match "lane_provider_rate_limited"

            if ($is429) {
                Write-Host "LaneLock 429 detail: $detail" -ForegroundColor DarkYellow
            }

            if (-not $is429 -or $attempt -ge $MaxAttempts) {
                if ($is429) {
                    throw "LaneLock provider remained rate-limited. Last provider detail: $detail"
                }
                throw
            }

            $delay = $delays[[math]::Min($attempt - 1, $delays.Count - 1)]
            Write-Host "LaneLock provider throttled (429). Retrying in $delay seconds [$attempt/$MaxAttempts]..." -ForegroundColor Yellow
            Start-Sleep -Seconds $delay
        }
    }

    throw "LaneLock provider remained rate-limited after $MaxAttempts attempts."
}

$routeCases = @(
    @{ Name = "Houston"; Origin = @{ latitude = 29.7858; longitude = -95.8245 }; Destination = @{ latitude = 29.7604; longitude = -95.3698 } },
    @{ Name = "London"; Origin = @{ latitude = 51.5034; longitude = -0.1276 }; Destination = @{ latitude = 51.5287; longitude = -0.2417 } },
    @{ Name = "Paris"; Origin = @{ latitude = 48.8584; longitude = 2.2945 }; Destination = @{ latitude = 48.8738; longitude = 2.2950 } },
    @{ Name = "Lagos"; Origin = @{ latitude = 6.5244; longitude = 3.3792 }; Destination = @{ latitude = 6.4698; longitude = 3.5852 } },
    @{ Name = "Tokyo"; Origin = @{ latitude = 35.6812; longitude = 139.7671 }; Destination = @{ latitude = 35.7101; longitude = 139.8107 } },
    @{ Name = "Sydney"; Origin = @{ latitude = -33.8688; longitude = 151.2093 }; Destination = @{ latitude = -33.8568; longitude = 151.2153 } }
)

$failures = New-Object System.Collections.Generic.List[string]
$rows = New-Object System.Collections.Generic.List[object]
$houstonGeometry = $null

foreach ($case in $routeCases) {
    try {
        $call = Invoke-OpenMapsFunction -Name "route" -Body @{ origin = $case.Origin; destination = $case.Destination; localeTag = "en-US" }
        $r = $call.Response
        $route = $r.route
        $geometryCount = @($route.geometry).Count
        $maneuverCount = @($route.maneuvers).Count
        $alternativeCount = @($r.alternatives).Count

        $providerOk = $r.provider -eq "here"
        $trafficOk = $r.trafficAware -eq $true
        $routeOk = $null -ne $route -and [double]$route.distanceMeters -gt 0 -and [double]$route.durationSeconds -gt 0 -and $geometryCount -ge 2 -and $maneuverCount -ge 1
        $latencyOk = $call.LatencyMs -le 12000

        if (-not $providerOk) { $failures.Add("$($case.Name): HERE route provider missing") }
        if (-not $trafficOk) { $failures.Add("$($case.Name): trafficAware was not true") }
        if (-not $routeOk) { $failures.Add("$($case.Name): invalid route geometry/summary/maneuvers") }
        if (-not $latencyOk) { $failures.Add("$($case.Name): latency $($call.LatencyMs) ms exceeded 12000 ms") }

        if ($case.Name -eq "Houston") { $houstonGeometry = @($route.geometry) }

        $rows.Add([pscustomobject]@{
            Case = $case.Name
            Provider = $r.provider
            TrafficAware = $r.trafficAware
            DistanceKm = [math]::Round(([double]$route.distanceMeters / 1000.0), 1)
            DurationMin = [math]::Round(([double]$route.durationSeconds / 60.0), 1)
            Alternatives = $alternativeCount
            Maneuvers = $maneuverCount
            LatencyMs = $call.LatencyMs
            Passed = $providerOk -and $trafficOk -and $routeOk -and $latencyOk
        })
    } catch {
        $failures.Add("$($case.Name): route request failed - $($_.Exception.Message)")
        $rows.Add([pscustomobject]@{
            Case = $case.Name
            Provider = "-"
            TrafficAware = $false
            DistanceKm = 0
            DurationMin = 0
            Alternatives = 0
            Maneuvers = 0
            LatencyMs = 0
            Passed = $false
        })
    }
}

if ($null -ne $houstonGeometry -and $houstonGeometry.Count -ge 2) {
    try {
        $trafficCall = Invoke-OpenMapsFunction -Name "traffic-incidents" -Body @{ routeGeometry = $houstonGeometry; corridorMeters = 750; localeTag = "en-US" }
        $traffic = $trafficCall.Response
        $trafficProviderOk = $traffic.provider -eq "here"
        $incidents = @($traffic.incidents)

        if (-not $trafficProviderOk) { $failures.Add("Live traffic: HERE provider missing") }

        $rows.Add([pscustomobject]@{
            Case = "Live traffic incidents"
            Provider = $traffic.provider
            TrafficAware = $true
            DistanceKm = "-"
            DurationMin = "-"
            Alternatives = "-"
            Maneuvers = "Incidents=$($incidents.Count)"
            LatencyMs = $trafficCall.LatencyMs
            Passed = $trafficProviderOk
        })
    } catch {
        $failures.Add("Live traffic: request failed - $($_.Exception.Message)")
    }
} else {
    $failures.Add("Live traffic: Houston route geometry unavailable")
}

try {
    $laneTrace = @(
        @{ latitude = 29.78338; longitude = -95.73279 },
        @{ latitude = 29.78354; longitude = -95.72989 },
        @{ latitude = 29.78368; longitude = -95.72692 },
        @{ latitude = 29.78381; longitude = -95.72413 }
    )

    $laneCall = Invoke-LaneGuidanceWithRetry -Body @{ trace = $laneTrace; maneuverType = "Continue" }
    $lane = $laneCall.Response
    $capability = [string]$lane.capability
    $laneAccessOk = $capability -eq "available" -or $capability -eq "no_trustworthy_lane_guidance"
    $providersAttempted = @($lane.providersAttempted)
    $mapboxConfigured = $providersAttempted -contains "mapbox"
    $lanePassed = $laneAccessOk -and $mapboxConfigured

    if (-not $laneAccessOk) { $failures.Add("LaneLock: invalid capability: $capability") }
    if (-not $mapboxConfigured) { $failures.Add("LaneLock: Mapbox lane provider is not configured on the production gateway") }

    $rows.Add([pscustomobject]@{
        Case = "LaneLock OSM + Mapbox"
        Provider = $lane.provider
        TrafficAware = "-"
        DistanceKm = "-"
        DurationMin = "-"
        Alternatives = "-"
        Maneuvers = "$capability / mapbox=$mapboxConfigured"
        LatencyMs = $laneCall.LatencyMs
        Passed = $lanePassed
    })
} catch {
    $failures.Add("LaneLock: request failed - $($_.Exception.Message)")
}

$rows | Format-Table -AutoSize
Write-Host ""

if ($failures.Count -gt 0) {
    Write-Host "PRODUCTION ROUTING + TRAFFIC ACCEPTANCE: FAILED" -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host " - $failure" -ForegroundColor Red
    }
    exit 1
}

Write-Host "PRODUCTION ROUTING + TRAFFIC ACCEPTANCE: PASSED" -ForegroundColor Green
Write-Host "Provider-backed alternatives are requested on every route; a corridor may legitimately return zero alternatives." -ForegroundColor DarkGray
exit 0
