#!/bin/bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$PROJECT_ROOT/iosApp"
python3 "$PROJECT_ROOT/scripts/ios/check_project.py"
xcodegen generate --spec project.yml
bundle install
bundle exec pod install
mkdir -p build
# 自动选取已安装的 iPhone 模拟器，避免锁死某一个型号或系统版本。
SIMULATOR_ID="$(xcrun simctl list devices available --json | python3 -c 'import json,sys; d=json.load(sys.stdin); print(next(x["udid"] for v in d["devices"].values() for x in v if x["name"].startswith("iPhone")))')"
xcodebuild test -workspace EyeProject.xcworkspace -scheme EyeProject \
  -destination "platform=iOS Simulator,id=$SIMULATOR_ID" \
  -derivedDataPath build/DerivedData -resultBundlePath "build/ios-$(date +%s).xcresult" \
  CODE_SIGNING_ALLOWED=NO
