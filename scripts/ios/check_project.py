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

for path in (root / 'shared').rglob('*.kt'):
    if '/build/' in path.as_posix(): continue
    if 'commonMain' in path.parts:
        text = path.read_text(encoding='utf-8')
        if re.search(r'^import (android\.|java\.|javax\.)',text,re.M):
            errors.append('Platform dependency in common source: ' + str(path.relative_to(root)))

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
if '../../shared/feedback/res' not in (root / 'feature/services/build.gradle.kts').read_text(encoding='utf-8'):
    errors.append('Android feedback sounds must use the shared asset directory')
if '../shared/feedback/res/raw' not in (root / 'iosApp/project.yml').read_text(encoding='utf-8'):
    errors.append('iOS feedback sounds must use the shared asset directory')

if errors:
    for error in errors: print(error)
    sys.exit(1)
print('Shared platform boundaries, theme tokens, model hash, SDK versions and iOS source inventory passed.')
