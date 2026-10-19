param()

$ErrorActionPreference = "Stop"
$rows = New-Object System.Collections.Generic.List[object]
$failures = New-Object System.Collections.Generic.List[string]

function Add-Result([string]$Check, [bool]$Passed, [string]$Detail) {
  $rows.Add([pscustomobject]@{ Check = $Check; Passed = $Passed; Detail = $Detail })
  if (-not $Passed) { $failures.Add(("{0}: {1}" -f $Check, $Detail)) }
}

$build = Get-Content "app/build.gradle.kts" -Raw
$bundlePath = "app/build/outputs/bundle/release/app-release.aab"

Add-Result "Release version no longer phase1" (-not $build.Contains('versionName = "0.1.0-phase1"')) "beta/release metadata is provider-configurable"
Add-Result "Version name configurable" ($build.Contains("OPENMAPS_VERSION_NAME") -and $build.Contains("0.9.0-beta.1")) "default beta version"
Add-Result "Version code configurable" ($build.Contains("OPENMAPS_VERSION_CODE") -and $build.Contains('"9001"')) "default beta version code"
Add-Result "R8/minification enabled" ($build.Contains("isMinifyEnabled = true")) "release build minified"
Add-Result "Resource shrinking enabled" ($build.Contains("isShrinkResources = true")) "release resources shrunk"
Add-Result "Signing externalized" ($build.Contains("OPENMAPS_RELEASE_STORE_FILE") -and $build.Contains("OPENMAPS_RELEASE_KEY_ALIAS")) "keystore and passwords remain external"
Add-Result "Signing verification task" ($build.Contains('tasks.register("verifyReleaseSigningConfiguration")')) "credential gate"
Add-Result "Combined production gate" ($build.Contains('tasks.register("verifyProductionReleaseConfiguration")')) "map + ARCore + signing + version"
Add-Result "Release bundle exists" (Test-Path $bundlePath) $bundlePath

if (Test-Path $bundlePath) {
  $size = (Get-Item $bundlePath).Length
  Add-Result "Release bundle non-empty" ($size -gt 1MB) ("{0:N1} MiB" -f ($size / 1MB))

  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $bundlePath))
  try {
    $names = @($zip.Entries | ForEach-Object { $_.FullName })
    Add-Result "AAB bundle config present" ($names -contains "BundleConfig.pb") "BundleConfig.pb"
    Add-Result "AAB base manifest present" ($names -contains "base/manifest/AndroidManifest.xml") "base/manifest/AndroidManifest.xml"
  } finally {
    $zip.Dispose()
  }
}

$rows | Format-Table -AutoSize
Write-Host ""
if ($failures.Count -gt 0) {
  Write-Host "RELEASE ARTIFACT STRUCTURAL ACCEPTANCE: FAILED" -ForegroundColor Red
  foreach ($failure in $failures) { Write-Host " - $failure" -ForegroundColor Red }
  exit 1
}
Write-Host "RELEASE ARTIFACT STRUCTURAL ACCEPTANCE: PASSED" -ForegroundColor Green
Write-Host "NOTE: CI validates the release code path with an unsigned AAB; final signing is verified separately with production credentials." -ForegroundColor Yellow
exit 0
