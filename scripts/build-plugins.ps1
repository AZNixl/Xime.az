# Build all Lua plugin xipk packages.
#
# Usage:
#   .\scripts\build-plugins.ps1
#
# Output: build/plugin-release/*.xipk
#
# Lua plugins are plain file directories (plugins/<name>/ containing
# manifest.yaml), no Gradle build needed. Each directory is zipped
# into a .xipk, mirroring scripts/build-plugins.sh.

$ErrorActionPreference = "Stop"

$PROJECT_DIR = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$OUTPUT_DIR = Join-Path $PROJECT_DIR "build\plugin-release"
$PLUGINS_DIR = Join-Path $PROJECT_DIR "plugins"

Write-Host "=== Building Lua plugin xipk packages ==="
Write-Host "Output dir: $OUTPUT_DIR"

New-Item -ItemType Directory -Force -Path $OUTPUT_DIR | Out-Null

foreach ($pluginDir in Get-ChildItem -Path $PLUGINS_DIR -Directory) {
    $manifest = Join-Path $pluginDir.FullName "manifest.yaml"
    if (-not (Test-Path $manifest)) { continue }

    $name = $pluginDir.Name
    $version = Get-Content -Path $manifest |
        Where-Object { $_ -match '^\s*version:' } |
        Select-Object -First 1 |
        ForEach-Object { ($_ -replace '^\s*version:\s*', '').Trim('"', "'") }
    if (-not $version) { $version = "0.0.0" }

    $out = Join-Path $OUTPUT_DIR "$name-$version.xipk"
    Remove-Item -Path $out -Force -ErrorAction SilentlyContinue

    $items = Get-ChildItem -Path $pluginDir.FullName -Force |
        Where-Object { -not $_.Name.StartsWith('.') }
    if (-not $items) { continue }

    $zip = Join-Path $OUTPUT_DIR "$name-$version.zip"
    Remove-Item -Path $zip -Force -ErrorAction SilentlyContinue
    Compress-Archive -Path $items.FullName -DestinationPath $zip -CompressionLevel Optimal
    Rename-Item -Path $zip -NewName "$name-$version.xipk"
    Write-Host "Lua : $name-$version.xipk"
}

Write-Host ""
Write-Host "=== Done ==="
Get-ChildItem -Path $OUTPUT_DIR -Filter "*.xipk" -File |
    Sort-Object Name |
    ForEach-Object { "{0,10:N1} K  {1}" -f ($_.Length / 1KB), $_.Name }
