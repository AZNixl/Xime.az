#!/bin/bash
# 构建所有插件的 release 包（.xipk）
#
# 使用方式：
#   bash scripts/build-plugins.sh
#
# 产物输出到 build/plugin-release/*.xipk

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT_DIR="$PROJECT_DIR/build/plugin-release"
PLUGIN_MODULES=(
  ":plugins:funasr-asr"
  ":plugins:kaomoji"
  ":plugins:meme-bunny"
)

echo "=== 构建插件 release 包 ==="
echo "输出目录: $OUTPUT_DIR"

mkdir -p "$OUTPUT_DIR"

# 构建所有插件 release 变体
"$PROJECT_DIR/gradlew" "${PLUGIN_MODULES[@]/%/:assembleRelease}" --quiet

# 收集 xipk 产物
find "$PROJECT_DIR/plugins" -path "*/outputs/apk/release/*.xipk" -exec cp {} "$OUTPUT_DIR/" \;

echo ""
echo "=== 完成 ==="
echo "插件包列表:"
ls -lh "$OUTPUT_DIR"/*.xipk
