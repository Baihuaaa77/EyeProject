param([switch]$Test)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$avdName = 'EyeProject_API30'
$sdkRoot = $env:ANDROID_SDK_ROOT
if (-not $sdkRoot) {
    $sdkLine = Get-Content (Join-Path $projectRoot 'local.properties') | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if (-not $sdkLine) { throw '找不到 Android SDK 路径。' }
    $sdkRoot = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\', '/'
}
$emulator = Join-Path $sdkRoot 'emulator\emulator.exe'
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path $emulator) -or -not (Test-Path $adb)) {
    throw 'Android Emulator 或 adb 尚未安装。'
}

if (-not $env:ANDROID_AVD_HOME) {
    if ($env:ANDROID_USER_HOME) {
        $env:ANDROID_AVD_HOME = Join-Path $env:ANDROID_USER_HOME 'avd'
    } else {
        $env:ANDROID_AVD_HOME = Join-Path $env:USERPROFILE '.android\avd'
    }
}
if (-not (Test-Path (Join-Path $env:ANDROID_AVD_HOME "$avdName.ini"))) {
    throw "找不到 $avdName；当前 AVD 目录：$env:ANDROID_AVD_HOME"
}

$running = @(& $adb devices | Where-Object { $_ -match '^emulator-\d+\s+(device|offline)$' })
if ($running.Count -eq 0) {
    Start-Process -FilePath $emulator -ArgumentList @('-avd', $avdName) -WindowStyle Normal | Out-Null
    Write-Host "正在启动 $avdName ..."
} elseif ($running.Count -gt 1) {
    throw '检测到多个模拟器，请只保留 EyeProject_API30。'
} else {
    Write-Host '模拟器已经在运行。'
}

$ready = $false
for ($i = 0; $i -lt 90; $i++) {
    $serialLine = @(& $adb devices | Where-Object { $_ -match '^emulator-\d+\s+device$' } | Select-Object -First 1)
    if ($serialLine.Count -gt 0) {
        $serial = ($serialLine[0] -split '\s+')[0]
        $boot = (& $adb -s $serial shell getprop sys.boot_completed).Trim()
        if ($boot -eq '1') { $ready = $true; break }
    }
    Start-Sleep -Seconds 2
}
if (-not $ready) { throw '模拟器在 3 分钟内未完成启动，请查看模拟器窗口。' }
Write-Host "模拟器已启动：$serial"

if ($Test) {
    & (Join-Path $PSScriptRoot 'Test-Local.ps1') -Mode EmulatorSmoke
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    # Gradle 仪器化测试结束时会卸载被测 APK，重新安装以便立即手工体验。
    $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    & $adb -s $serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'Debug APK 安装失败。' }
    & $adb -s $serial shell am start -n 'com.zkyjss.eyeproject/.MainActivity'
    if ($LASTEXITCODE -ne 0) { throw '应用启动失败。' }
    Write-Host 'Debug 应用已在模拟器中打开。首次使用请允许相机权限。'
}
