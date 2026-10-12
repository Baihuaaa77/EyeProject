$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

& (Join-Path $PSScriptRoot 'Start-Emulator.ps1')
if ($LASTEXITCODE -ne 0) { throw '模拟器启动失败。' }

$sdkRoot = $env:ANDROID_SDK_ROOT
if (-not $sdkRoot) {
    $sdkLine = Get-Content (Join-Path $projectRoot 'local.properties') | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if (-not $sdkLine) { throw '找不到 Android SDK 路径。' }
    $sdkRoot = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\', '/'
}
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
$python = Get-Command python -ErrorAction Stop
$apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') ':app:assembleDebug' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'Debug APK 构建失败。' }
    $devices = @(& $adb devices | Where-Object { $_ -match '^emulator-\d+\s+device$' })
    if ($devices.Count -ne 1) { throw '需要恰好一个正在运行的模拟器。' }
    $serial = ($devices[0] -split '\s+')[0]
    & $adb -s $serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'Debug APK 安装失败。' }
    & $adb -s $serial shell pm grant com.zkyjss.eyeproject android.permission.CAMERA
    if ($LASTEXITCODE -ne 0) { throw '相机权限授予失败。' }
    & $adb -s $serial shell am start -n 'com.zkyjss.eyeproject/.MainActivity'
    if ($LASTEXITCODE -ne 0) { throw '应用启动失败。' }
    Write-Host '请在模拟器中点击开始监测；电脑上将打开独立的前摄输入窗口。'
    & $python.Source (Join-Path $PSScriptRoot 'show_camera_feed.py') --adb $adb --serial $serial
    if ($LASTEXITCODE -ne 0) { throw '调试预览窗口退出异常。' }
}
finally {
    Pop-Location
}
