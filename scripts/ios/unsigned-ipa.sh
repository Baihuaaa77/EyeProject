#!/bin/bash
# 生成未签名的真机 IPA：不需要 Apple Developer Program。
# 安装前由 Sideloadly / AltStore 用个人 Apple ID 重新签名（免费签名 7 天有效）。
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$PROJECT_ROOT/iosApp"
# verify.sh 已生成工程并安装 Pods 时直接复用；单独运行时补齐
if [[ ! -d EyeProject.xcworkspace ]]; then
  python3 "$PROJECT_ROOT/scripts/ios/check_project.py"
  xcodegen generate --spec project.yml
  bundle install
  bundle exec pod install
fi
# Release 配置：共用核心与 Swift 都开启优化，真机性能与正式包一致。
xcodebuild build -workspace EyeProject.xcworkspace -scheme EyeProject -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath build/DeviceData \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=""
APP="build/DeviceData/Build/Products/Release-iphoneos/EyeProject.app"
test -f "$APP/EyeProject"
rm -rf build/unsigned && mkdir -p build/unsigned/Payload
cp -R "$APP" build/unsigned/Payload/
(cd build/unsigned && zip -qry EyeProject-unsigned.ipa Payload)
echo "未签名 IPA：iosApp/build/unsigned/EyeProject-unsigned.ipa"
