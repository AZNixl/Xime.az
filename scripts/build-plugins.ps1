# 构建所有插件的 release 包（.xipk）
#
# 使用方式：
#   .\scripts\build-plugins.ps1
#
# 产物输出到 build/plugin-release/*.xipk

$ErrorActionPreference = "Stop"

$PROJECT_DIR = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$OUTPUT_DIR = Join-Path $PROJECT_DIR "build\plugin-release"
$PLUGIN_MODULES = @(
  ":plugins:funasr-asr"
  ":plugins:kaomoji"
  ":plugins:meme-bunny"
)

Write-Host "=== Building plugin release packages ==="
Write-Host "Output dir: $OUTPUT_DIR"

New-Item -ItemType Directory -Force -Path $OUTPUT_DIR | Out-Null

# Build all plugin release variants
$gradlew = Join-Path $PROJECT_DIR "gradlew.bat"
$tasks = $PLUGIN_MODULES | ForEach-Object { "$_`:assembleRelease" }
& $gradlew @tasks --quiet
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Collect xipk artifacts
$pluginsDir = Join-Path $PROJECT_DIR "plugins"
$xipkFiles = Get-ChildItem -Path $pluginsDir -Recurse -Filter "*.xipk" -File |
    Where-Object { $_.FullName -like "*outputs\apk\release*" }
foreach ($file in $xipkFiles) {
    Copy-Item -Path $file.FullName -Destination $OUTPUT_DIR -Force
}

Write-Host ""
Write-Host "=== Done ==="
Write-Host "Plugin packages:"
Get-ChildItem -Path $OUTPUT_DIR -Filter "*.xipk" -File |
    Sort-Object Name |
    ForEach-Object { "{0,10:N1} K  {1}" -f ($_.Length / 1KB), $_.Name }
