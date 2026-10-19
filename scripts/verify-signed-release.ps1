param(
  [Parameter(Mandatory = $false)]
  [string]$Artifact = "app/build/outputs/bundle/release/app-release.aab"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Artifact)) {
  Write-Error "Release artifact not found: $Artifact"
  exit 1
}

$resolved = (Resolve-Path $Artifact).Path
$extension = [System.IO.Path]::GetExtension($resolved).ToLowerInvariant()

if ($extension -eq ".aab") {
  $jarsigner = Get-Command jarsigner -ErrorAction SilentlyContinue
  if ($null -eq $jarsigner) {
    Write-Error "jarsigner is required to verify an Android App Bundle signature."
    exit 1
  }

  $verifyOutput = (& $jarsigner.Source -verify -verbose -certs $resolved 2>&1 | Out-String)
  if ($LASTEXITCODE -ne 0 -or
      $verifyOutput -match "jar is unsigned" -or
      $verifyOutput -notmatch "jar verified") {
    Write-Host $verifyOutput
    Write-Error "AAB signature verification failed."
    exit 1
  }
  Write-Host "AAB SIGNATURE: VERIFIED" -ForegroundColor Green
} elseif ($extension -eq ".apk") {
  $apksigner = Get-Command apksigner -ErrorAction SilentlyContinue
  if ($null -eq $apksigner -and -not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
    $buildTools = Join-Path $env:ANDROID_HOME "build-tools"
    if (Test-Path $buildTools) {
      $candidate = Get-ChildItem $buildTools -Directory |
        Sort-Object Name -Descending |
        ForEach-Object {
          $bat = Join-Path $_.FullName "apksigner.bat"
          $unix = Join-Path $_.FullName "apksigner"
          if (Test-Path $bat) { $bat } elseif (Test-Path $unix) { $unix }
        } |
        Select-Object -First 1
      if ($candidate) { $apksigner = Get-Item $candidate }
    }
  }
  if ($null -eq $apksigner) {
    Write-Error "apksigner is required to verify APK v2/v3/v4 signatures."
    exit 1
  }

  & $apksigner.FullName verify --verbose --print-certs $resolved
  if ($LASTEXITCODE -ne 0) {
    Write-Error "APK signature verification failed."
    exit 1
  }
  Write-Host "APK SIGNATURE: VERIFIED" -ForegroundColor Green
} else {
  Write-Error "Unsupported artifact type: $extension. Expected .aab or .apk."
  exit 1
}

$hash = Get-FileHash -Algorithm SHA256 -Path $resolved
Write-Host ("SHA-256: {0}" -f $hash.Hash)
Write-Host ("Artifact: {0}" -f $resolved)
exit 0
