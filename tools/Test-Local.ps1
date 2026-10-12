param(
    [ValidateSet('Local', 'EmulatorSmoke')]
    [string]$Mode = 'Local',
    [switch]$Fresh
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $projectRoot 'gradlew.bat'

if (-not (Test-Path -LiteralPath $gradle)) {
    throw "找不到 Gradle 启动脚本：$gradle"
}

Push-Location $projectRoot
try {
    if ($Mode -eq 'Local') {
        $gradleArgs = @(
            ':feature:services:testDebugUnitTest',
            ':feature:fatigue:testDebugUnitTest',
            ':app:assembleDebug'
        )
        if ($Fresh) { $gradleArgs += '--rerun-tasks' }
        & $gradle @gradleArgs
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

        Write-Host '本地逻辑与 Compose 测试通过。页面截图：'
        Write-Host (Join-Path $projectRoot 'feature\services\build\reports\ui')
        Write-Host (Join-Path $projectRoot 'feature\fatigue\build\reports\ui')
        Write-Host 'Debug APK：'
        Write-Host (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk')
        exit 0
    }

    $propertiesFile = Join-Path $projectRoot 'local.properties'
    if (-not (Test-Path -LiteralPath $propertiesFile)) {
        throw '缺少 local.properties；请先在 Android Studio 中配置 Android SDK。'
    }
    $sdkLine = Get-Content -LiteralPath $propertiesFile | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if (-not $sdkLine) { throw 'local.properties 中缺少 sdk.dir。' }
    $sdkPath = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\', '/'
    $adb = Join-Path $sdkPath 'platform-tools\adb.exe'
    if (-not (Test-Path -LiteralPath $adb)) { throw "找不到 adb：$adb" }

    $devices = @(& $adb devices | Where-Object { $_ -match '^emulator-\d+\s+device$' })
    if ($devices.Count -ne 1) {
        throw "请先在 Android Studio 中启动一个 AVD，且只保留一个运行中的模拟器；当前检测到 $($devices.Count) 个。"
    }
    $serial = ($devices[0] -split '\s+')[0]
    $allDevices = @(& $adb devices | Where-Object { $_ -match '^\S+\s+device$' })
    if ($allDevices.Count -ne 1) {
        throw '当前还有其他已连接设备。请断开其他设备，避免 Gradle 在多台设备上运行测试。'
    }
    $abis = (& $adb -s $serial shell getprop ro.product.cpu.abilist).Trim()
    if (($abis -split ',') -notcontains 'x86_64') {
        throw "该 AVD 不支持 Debug 包的 x86_64 原生库（当前 ABI：$abis）。请使用 x86_64 系统镜像。"
    }

    & $gradle ':app:connectedDebugAndroidTest' '-Pandroid.testInstrumentationRunnerArguments.class=com.zkyjss.eyeproject.CameraPipelineAndroidTest'
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host '模拟器前摄与 MediaPipe 帧回调测试通过。'
    Write-Host '此测试不覆盖眼部测量精度或 GPU 模型推理。'
}
finally {
    Pop-Location
}
