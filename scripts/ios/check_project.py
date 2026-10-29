"""Windows 和 CI 都可运行的跨平台工程检查，不代替 Xcode 编译。"""
from pathlib import Path
import hashlib
import re
import sys
import plistlib
import wave

root = Path(__file__).resolve().parents[2]
errors = []
colors = (root / 'core/designsystem/src/main/java/com/zkyjss/eyeproject/core/designsystem/theme/Color.kt').read_text(encoding='utf-8')
light = colors.split('val LightEyeColors = EyeColors(', 1)[1].split('val DarkEyeColors', 1)[0]
palette = dict(re.findall(r'(\w+)\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)', light))
swift = (root / 'iosApp/Sources/EyeStyle.swift').read_text(encoding='utf-8')
mapping = {'background':'appBackground','panel':'panelBackground','text':'textPrimary','secondary':'textSecondary',
    'surfaceVariant':'surfaceVariant','primary':'primary','brandStart':'brandStart','brandEnd':'brandEnd','accent':'accent','warning':'warning','success':'success',
    'tooClose':'tooClose','tooFar':'tooFar'}
for key, value in mapping.items():
    match = re.search(rf'static let {key} = Color\(rgb: 0x([0-9A-Fa-f]{{6}})\)', swift)
    if match is None or match.group(1).upper() != palette[value].upper():
        errors.append(f'iOS theme token drift: {key}')

model = root / 'core/camera/src/main/assets/face_landmarker.task'
expected = '64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff'
if hashlib.sha256(model.read_bytes()).hexdigest() != expected:
    errors.append('MediaPipe model changed: review both platform fixtures and update model manifest')
versions = (root / 'gradle/libs.versions.toml').read_text(encoding='utf-8')
pod = (root / 'iosApp/Podfile').read_text(encoding='utf-8')
android_sdk = re.search(r'^mediapipe = "([^"]+)"',versions,re.M).group(1)
ios_sdk = re.search(r"pod 'MediaPipeTasksVision', '([^']+)'",pod).group(1)
if android_sdk != ios_sdk:
    errors.append('MediaPipe SDK versions differ')

C_MACROS = {
    'ARG_MAX', 'CHILD_MAX', 'LINK_MAX', 'MAX_CANON', 'MAX_INPUT', 'NAME_MAX', 'NGROUPS_MAX', 'OPEN_MAX', 'PATH_MAX',
    'PIPE_BUF', 'LINE_MAX', 'RE_DUP_MAX', 'IOV_MAX', 'CHAR_BIT', 'CHAR_MAX', 'CHAR_MIN', 'INT_MAX', 'INT_MIN', 'LONG_MAX',
    'LONG_MIN', 'SHRT_MAX', 'UINT_MAX', 'BUFSIZ', 'EOF', 'FILENAME_MAX', 'TMP_MAX', 'NULL', 'TRUE', 'FALSE', 'YES', 'NO',
    'MAXPATHLEN', 'NBBY', 'DEBUG', 'NDEBUG', 'TARGET_OS_IOS', 'M_PI', 'M_E', 'INFINITY', 'NAN', 'HUGE_VAL',
    'EXIT_SUCCESS', 'EXIT_FAILURE', 'RAND_MAX', 'SIG_DFL', 'SIG_IGN', 'CLOCKS_PER_SEC', 'UINT8_MAX', 'INT32_MAX', 'INT64_MAX',
}
for path in (root / 'shared').rglob('*.kt'):
    if '/build/' in path.as_posix(): continue
    if 'commonMain' in path.parts:
        text = path.read_text(encoding='utf-8')
        if re.search(r'^import (android\.|java\.|javax\.)',text,re.M):
            errors.append('Platform dependency in common source: ' + str(path.relative_to(root)))
        # 导出到 iOS 框架的常量会写进 Objective-C 头文件，与苹果 SDK 的 C 宏同名就会被宏替换、头文件编译失败
        # （曾因 Chats.MAX_INPUT 撞上 <sys/syslimits.h> 的 MAX_INPUT）
        for name in set(re.findall(r'\bconst val ([A-Z][A-Z0-9_]*)\b', text)) & C_MACROS:
            errors.append('Constant name clashes with an Apple SDK C macro (' + name + '): ' + str(path.relative_to(root)))

sources = list((root / 'iosApp/Sources').glob('*.swift'))
if len(sources) < 6: errors.append('Missing iOS source files')
with (root / 'iosApp/Sources/PrivacyInfo.xcprivacy').open('rb') as stream:
    plistlib.load(stream)
icon = root / 'iosApp/Sources/Assets.xcassets/AppIcon.appiconset/AppIcon.png'
png = icon.read_bytes()
if png[:8] != b'\x89PNG\r\n\x1a\n' or int.from_bytes(png[16:20],'big') != 1024 or png[25] != 2:
    errors.append('iOS icon must be opaque RGB 1024px PNG')
for path in (root / 'scripts/ios').glob('*.sh'):
    if '\r' in path.read_text(encoding='utf-8'): errors.append('Shell script contains CRLF: ' + path.name)

feedback_source = root / 'shared/screening/src/commonMain/kotlin/com/zkyjss/eyeproject/feature/services/ScreeningFeedback.kt'
for name in set(re.findall(r'"(eye_[a-z]+)"', feedback_source.read_text(encoding='utf-8'))):
    path = root / 'shared/feedback/res/raw' / (name + '.wav')
    try:
        with wave.open(str(path)) as sound:
            if (sound.getnchannels(), sound.getsampwidth(), sound.getframerate()) != (1, 2, 22050) or sound.getnframes() / 22050 > 0.35:
                errors.append('Invalid short feedback sound: ' + name)
    except (OSError, wave.Error): errors.append('Missing feedback sound: ' + name)
# 小瞳互动音：两端引用的音效都要在共用目录，格式同提示音
mascot_sources = [root / 'core/ui/src/main/java/com/zkyjss/eyeproject/core/ui/component/XiaoTong.kt', root / 'iosApp/Sources/MascotStage.swift']
for name in sorted({n for f in mascot_sources for n in re.findall(r'"(eye_[a-z]+)"', f.read_text(encoding='utf-8'))}):
    path = root / 'shared/feedback/res/raw' / (name + '.wav')
    try:
        with wave.open(str(path)) as sound:
            if (sound.getnchannels(), sound.getsampwidth(), sound.getframerate()) != (1, 2, 22050) or sound.getnframes() / 22050 > 0.35:
                errors.append('Invalid mascot sound: ' + name)
    except (OSError, wave.Error): errors.append('Missing mascot sound: ' + name)
for module in ('feature/services', 'core/ui'):
    if '../../shared/feedback/res' not in (root / module / 'build.gradle.kts').read_text(encoding='utf-8'):
        errors.append('Android sounds must use the shared asset directory: ' + module)
if '../shared/feedback/res/raw' not in (root / 'iosApp/project.yml').read_text(encoding='utf-8'):
    errors.append('iOS feedback sounds must use the shared asset directory')

if errors:
    for error in errors: print(error)
    sys.exit(1)
print('Shared platform boundaries, theme tokens, model hash, SDK versions and iOS source inventory passed.')
