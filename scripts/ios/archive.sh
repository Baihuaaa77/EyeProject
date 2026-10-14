#!/bin/bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${APPLE_TEAM_ID:?请配置 Apple Developer Team ID}"
: "${IOS_BUNDLE_ID:?请配置已注册的 Bundle ID}"
: "${IOS_EXPORT_OPTIONS_PLIST:?请提供包含团队和签名配置的 ExportOptions.plist 路径}"
test -f "$IOS_EXPORT_OPTIONS_PLIST"
cd "$PROJECT_ROOT/iosApp"
python3 "$PROJECT_ROOT/scripts/ios/check_project.py"
xcodegen generate --spec project.yml
bundle install
bundle exec pod install
mkdir -p build
SIGNING_OPTIONS=()
if [[ -n "${IOS_PROFILE_NAME:-}" ]]; then
  SIGNING_OPTIONS+=(CODE_SIGN_STYLE=Manual "PROVISIONING_PROFILE_SPECIFIER=$IOS_PROFILE_NAME" "CODE_SIGN_IDENTITY=Apple Distribution")
fi
xcodebuild archive -workspace EyeProject.xcworkspace -scheme EyeProject \
  -destination 'generic/platform=iOS' -archivePath build/EyeProject.xcarchive \
  DEVELOPMENT_TEAM="$APPLE_TEAM_ID" PRODUCT_BUNDLE_IDENTIFIER="$IOS_BUNDLE_ID" \
  "${SIGNING_OPTIONS[@]}" -allowProvisioningUpdates
# 导出配置必须由账号持有人提供；TestFlight 用 app-store-connect，登记设备测试用 release-testing。
xcodebuild -exportArchive -archivePath build/EyeProject.xcarchive \
  -exportPath build/export -exportOptionsPlist "$IOS_EXPORT_OPTIONS_PLIST" -allowProvisioningUpdates
