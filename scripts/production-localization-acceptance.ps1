$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$res = Join-Path $root "app/src/main/res"
$failures = New-Object System.Collections.Generic.List[string]
$rows = New-Object System.Collections.Generic.List[object]

function Get-StringNames([string]$Path) {
    [xml]$xml = Get-Content -Raw -Path $Path
    return @($xml.resources.string | ForEach-Object { [string]$_.name } | Sort-Object -Unique)
}

$basePath = Join-Path $res "values/strings.xml"
$base = Get-StringNames $basePath

foreach ($folder in @("values-es", "values-fr", "values-ar", "values-he")) {
    $path = Join-Path $res "$folder/strings.xml"
    if (-not (Test-Path $path)) {
        $failures.Add("$folder: missing language pack")
        continue
    }
    $names = Get-StringNames $path
    $missing = @($base | Where-Object { $_ -notin $names })
    $extra = @($names | Where-Object { $_ -notin $base })
    $ok = $missing.Count -eq 0 -and $extra.Count -eq 0
    if ($missing.Count -gt 0) { $failures.Add("$folder: missing $($missing.Count) strings: $($missing -join ', ')") }
    if ($extra.Count -gt 0) { $failures.Add("$folder: has $($extra.Count) unexpected strings: $($extra -join ', ')") }
    $rows.Add([pscustomobject]@{
        Pack = $folder
        Strings = $names.Count
        Missing = $missing.Count
        Extra = $extra.Count
        Passed = $ok
    })
}

[xml]$localeConfig = Get-Content -Raw -Path (Join-Path $res "xml/locales_config.xml")
$declared = @($localeConfig.'locale-config'.locale | ForEach-Object { [string]$_.'android:name' })
foreach ($locale in @("en-US", "en-GB", "es", "fr", "ar", "he")) {
    if ($locale -notin $declared) { $failures.Add("locales_config.xml: missing $locale") }
}

$manifest = Get-Content -Raw -Path (Join-Path $root "app/src/main/AndroidManifest.xml")
if ($manifest -notmatch 'android:supportsRtl="true"') {
    $failures.Add("AndroidManifest.xml: RTL support is not enabled")
}
if ($manifest -notmatch 'android:localeConfig="@xml/locales_config"') {
    $failures.Add("AndroidManifest.xml: per-app locale configuration is not wired")
}

$rows | Format-Table -AutoSize
Write-Host ""
Write-Host "Declared locales: $($declared -join ', ')"

if ($failures.Count -gt 0) {
    Write-Host "WORLDWIDE LOCALIZATION ACCEPTANCE: FAILED" -ForegroundColor Red
    foreach ($failure in $failures) { Write-Host " - $failure" -ForegroundColor Red }
    exit 1
}

Write-Host "WORLDWIDE LOCALIZATION ACCEPTANCE: PASSED" -ForegroundColor Green
exit 0
