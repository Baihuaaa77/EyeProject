#if SCREENSHOT_CAPTURE
import SwiftUI
import UIKit
import XCTest
@testable import EyeProject

/// 在 iPhone 模拟器窗口中逐张拍摄真实页面；演示快照不启动相机、不发送消息、不保存测试记录。
@MainActor
final class ScreenshotCatalogTests: XCTestCase {
    private var window: UIWindow!
    private var rows: [[String: Any]] = []
    // 模拟器 XCTest 与源代码共用宿主文件系统，直接写到宿主导出目录，避免测试结束后设备已关闭而无法取容器。
    private let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("build/page-captures", isDirectory: true)

    func testCaptureAllPages() async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        window = UIWindow(windowScene: scene)
        window.frame = UIScreen.main.bounds
        window.windowLevel = .alert + 1
        defer { window.isHidden = true; window.rootViewController = nil }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        UserDefaults.standard.set(18.0, forKey: "eye.pixelsPerMm")
        let me = SubjectProfileItem(id: "capture-self", relation: "SELF", nickname: "示例用户",
            fields: ["出生年份": "1986", "性别": "女", "职业": "办公室工作", "日常矫正方式": "框架眼镜", "既往眼病": "无"], updatedAt: 0)
        SubjectStore.shared.capture([me, SubjectProfileItem(id: "capture-family", relation: "PARENT", nickname: "示例家人", fields: ["出生年份": "1966", "性别": "男"], updatedAt: 0)])

        for dark in [false, true] {
            window.overrideUserInterfaceStyle = dark ? .dark : .light
            seedHome()
            CloudModel.shared.capture(state: ["configured": false, "signedIn": false], chat: ["state": "ready", "messages": []])
            try await capture("01-首页", CaptureMainPage(tab: .home), dark, scroll: true)
            try await capture("02-今日不适打卡", HomePage(captureFeeling: true), dark)
            try await capture("03-服务", CaptureMainPage(tab: .services), dark, scroll: true)
            try await capture("04-我的", CaptureMainPage(tab: .mine), dark, scroll: true)
            try await capture("05-用眼趋势", TrendPage(), dark, navigation: true, scroll: true)
            try await capture("06-健康建议", AdvicePage(openTest: { _ in }), dark, navigation: true, scroll: true)
            try await capture("07-屏幕光", BrightnessPage(), dark, navigation: true, scroll: true)
            try await capture("08-护眼操", EyeExerciseView(), dark)
            try await capture("09-账户登录", AccountPage(openCloudSync: {}), dark, navigation: true, scroll: true)
            try await capture("10-账户注册", SubPage(title: "账户") { SignInForm(captureMode: 1) }, dark, navigation: true)
            try await capture("11-家庭成员", SubjectsPage(edit: { _ in }), dark, navigation: true, scroll: true)
            try await capture("12-资料编辑", SubjectEditView(item: me), dark, navigation: true, scroll: true)
            try await capture("13-测试记录空态", RecordsPage(), dark, navigation: true)
            try await capture("14-云端同步", CloudSyncPage(openAccount: {}), dark, navigation: true, scroll: true)
            try await capture("15-权限与通知", PermissionsPage(), dark, navigation: true, scroll: true)
            try await capture("16-系统设置", SystemSettingsPage(voice: EyeVoice(), feedback: EyeFeedback()), dark, navigation: true, scroll: true)
            try await capture("17-欢迎页", WelcomeView(done: {}), dark, scroll: true)
            try await capture("18-AI问诊开场", AiChatPage(openAccount: {}), dark, navigation: true, scroll: true)
            seedChat()
            try await capture("19-AI问诊对话与选项", AiChatPage(openAccount: {}), dark, navigation: true, scroll: true)
            try await capture("20-AI问诊选项确认", AiChatPage(captureOption: "有些干涩"), dark, navigation: true)
            try await capture("21-AI问诊同意弹窗", AiChatPage(captureConsent: true), dark, navigation: true)

            try await testShot("30-屏幕尺寸校准", "DRY_EYE", "Setup", dark, calibrated: false)
            for kind in ["DRY_EYE", "PRESBYOPIA", "STRABISMUS"] {
                try await testShot("31-选择被测者-" + kind, kind, kind == "DRY_EYE" ? "Profile" : "PROFILE", dark)
                let runtime = fixture(kind, kind == "DRY_EYE" ? "Profile" : "PROFILE")
                try await capture("32-测前快问-" + kind, SubPage(title: "测前快问") { PreTestCheckView(runtime: runtime) }, dark, navigation: true, scroll: true)
                for (tag, cm) in [("距离合适", 40.0), ("太近了", 24.0), ("太远了", 62.0)] {
                    let target = kind == "STRABISMUS" ? 33.0 : 40.0
                    try await testShot("33-测试准备-\(kind)-\(tag)", kind, kind == "DRY_EYE" ? "Setup" : "ENVIRONMENT", dark,
                        values: ["distanceCm": tag == "距离合适" ? target : cm, "targetDistanceCm": target], scroll: true)
                }
            }

            try await testShot("40-干眼-视标练习演示", "DRY_EYE", "PracticeGuide", dark)
            try await testShot("41-干眼-练习作答", "DRY_EYE", "Practice", dark)
            try await testShot("42-干眼-正式方法演示", "DRY_EYE", "FormalGuide", dark)
            try await testShot("43-干眼-倒计时", "DRY_EYE", "Countdown", dark, values: ["secondsLeft": 3])
            for phase in ["Natural", "Open"] {
                for (tag, cm) in [("距离合适", 40.0), ("太近了", 24.0), ("太远了", 62.0)] {
                    try await testShot("44-干眼-\(phase == "Natural" ? "自然眨眼" : "持续睁眼")-\(tag)", "DRY_EYE", phase, dark, values: ["distanceCm": cm])
                }
            }
            try await testShot("45-干眼-看不到人脸", "DRY_EYE", "Natural", dark, values: ["faceDetected": false, "distanceCm": 0, "trialVisible": false])
            try await testShot("46-干眼-异常暂停", "DRY_EYE", "Natural", dark,
                values: ["paused": true, "gateReady": false, "pauseReason": "距离太近，请把手机往远处移", "distanceCm": 24])
            try await testShot("47-干眼-暂停后可继续", "DRY_EYE", "Natural", dark,
                values: ["paused": true, "gateReady": true, "pauseReason": "距离已恢复，可以继续测试"])
            try await testShot("48-干眼-闭眼休息", "DRY_EYE", "Rest", dark, values: ["secondsLeft": 38, "progress": 0.36, "targetDistanceCm": 0])
            try await testShot("49-干眼-复测演示", "DRY_EYE", "FormalGuide", dark, values: ["afterRest": true])

            let nearGuide: [String: Any] = ["guidePending": true, "stepTitle": "近视力", "guideText": "用手掌轻轻遮住一只眼，看 E 的开口方向，再换眼完成。"]
            try await testShot("50-老视-戴镜确认", "PRESBYOPIA", "NEAR_VISION", dark,
                values: nearGuide.merging(["glassesCheck": true, "glassesTitle": "确认戴镜方式", "glassesButton": "确认，继续测试"], uniquingKeysWith: { _, b in b }))
            try await testShot("51-老视-近视力演示", "PRESBYOPIA", "NEAR_VISION", dark, values: nearGuide)
            try await testShot("52-老视-遮盖准备", "PRESBYOPIA", "NEAR_VISION", dark,
                values: ["nearAwaitingEyeReady": true, "instruction": "遮住左眼，用右眼看屏幕"])
            for eye in ["RIGHT", "LEFT", "BOTH"] {
                try await testShot("53-老视-近视力-" + eye, "PRESBYOPIA", "NEAR_VISION", dark, values: ["eye": eye])
            }
            for (tag, cm) in [("太近了", 24.0), ("太远了", 62.0)] {
                try await testShot("54-老视-近视力-" + tag, "PRESBYOPIA", "NEAR_VISION", dark, values: ["distanceCm": cm, "trialVisible": false])
            }
            try await testShot("55-老视-移近法演示", "PRESBYOPIA", "PUSH_UP", dark,
                values: ["guidePending": true, "stepTitle": "移近测试", "guideText": "遮住一只眼，从 45 cm 外看 E，慢慢移近，持续模糊时确认。"])
            try await testShot("56-老视-移近法准备", "PRESBYOPIA", "PUSH_UP", dark, values: ["pushStage": "READY", "pushEye": "右眼", "distanceCm": 46, "targetDistanceCm": 45])
            try await testShot("57-老视-移近法进行中", "PRESBYOPIA", "PUSH_UP", dark, values: ["pushStage": "MOVING", "pushEye": "右眼", "pushDone": 1, "distanceCm": 29, "targetDistanceCm": 0])
            for distance in [67, 50, 33] {
                try await testShot("58-老视-多距离视力-\(distance)厘米", "PRESBYOPIA", "DISTANCE_CURVE", dark,
                    values: ["targetDistanceCm": distance, "distanceCm": distance])
            }
            try await testShot("59-老视-正在移动到新距离", "PRESBYOPIA", "DISTANCE_CURVE", dark,
                values: ["targetDistanceCm": 33, "distanceMoveToCm": 33, "distanceCm": 50, "trialVisible": false])
            for distance in [50, 25] {
                try await testShot("60-老视-远近切换-\(distance)厘米", "PRESBYOPIA", "FLIPPER", dark,
                    values: ["targetDistanceCm": distance, "distanceCm": distance, "secondsLeft": 42])
            }

            try await testShot("70-眼位-注视方法演示", "STRABISMUS", "FIXATION", dark,
                values: ["guidePending": true, "stepTitle": "注视校准", "guideText": "看着屏幕圆点，头和手机保持不动。"])
            let points: [(Double, Double)] = [(0.5,0.5),(0.08,0.08),(0.5,0.08),(0.92,0.08),(0.92,0.5),(0.92,0.92),(0.5,0.92),(0.08,0.92),(0.08,0.5)]
            for (index, point) in points.enumerated() {
                try await testShot("71-眼位-九点校准-\(index + 1)", "STRABISMUS", "GAZE_CALIBRATION", dark,
                    values: ["calibrationX": point.0, "calibrationY": point.1, "gazeCalibrationIndex": index])
            }
            try await testShot("72-眼位-校准暂停", "STRABISMUS", "GAZE_CALIBRATION", dark,
                values: ["calibrationPaused": true, "warning": "请保持头部稳定，校准已暂停"])
            try await testShot("73-眼位-校准失败重试", "STRABISMUS", "GAZE_CALIBRATION", dark,
                values: ["gazeCalibrationFailed": true, "warning": "还没有看稳，请重试注视校准"])
            try await testShot("74-眼位-正式注视", "STRABISMUS", "FIXATION", dark)
            for (tag, cm) in [("太近了", 20.0), ("太远了", 52.0)] {
                try await testShot("75-眼位-注视-" + tag, "STRABISMUS", "FIXATION", dark, values: ["distanceCm": cm])
            }
            try await testShot("76-眼位-看远准备", "STRABISMUS", "FAR_FIXATION", dark, values: ["farWaiting": true])
            try await testShot("77-眼位-看远计时", "STRABISMUS", "FAR_FIXATION", dark, values: ["farWaiting": false, "secondsLeft": 7])
            for selfCover in [false, true] {
                try await testShot("78-眼位-交替遮盖演示-\(selfCover ? "自行遮眼" : "协助者")", "STRABISMUS", "COVER", dark,
                    values: ["guidePending": true, "coverSelf": selfCover, "stepTitle": "交替遮眼", "guideText": "按提示遮右眼、移开、遮左眼，再交替遮眼，不压眼球。"])
            }
            let coverSteps = ["遮右眼", "移开遮盖，双眼注视", "遮左眼", "移开遮盖，双眼注视", "遮右眼", "遮左眼", "遮右眼", "遮左眼", "移开遮盖"]
            for (index, label) in coverSteps.enumerated() {
                try await testShot("79-眼位-交替遮盖步骤\(index + 1)", "STRABISMUS", "COVER", dark,
                    values: ["coverDone": index + 1, "coverHolding": true, "coverCurrent": label, "coverNext": coverSteps[min(index + 1, 8)],
                             "coverPrompt": label, "coverHoldMs": index < 4 ? 2000 : 1000, "coverRemainingMs": 650])
            }
            try await testShot("80-眼位-等待相机确认遮盖", "STRABISMUS", "COVER", dark, values: ["coverHolding": false, "coverNext": "遮右眼", "coverPrompt": "请协助者遮住右眼"])
            try await testShot("81-眼位-追踪演示", "STRABISMUS", "PURSUIT", dark,
                values: ["guidePending": true, "stepTitle": "追踪移动目标", "guideText": "只用眼睛跟随移动的圆点，头和手机保持不动。"])
            try await testShot("82-眼位-追踪移动目标", "STRABISMUS", "PURSUIT", dark)
            try await testShot("83-测试中退出确认", "STRABISMUS", "FIXATION", dark, exitDialog: true)

            try await testShot("90-色觉-测前准备", "COLOR_VISION", "Setup", dark)
            try await testShot("91-色觉-示例图", "COLOR_VISION", "Demo", dark,
                values: ["plateId": "demo", "options": ["12", "17", "72", "看不出"], "demo": true])
            for plate in ["p1", "d1"] {
                try await testShot("92-色觉-检测图-" + plate, "COLOR_VISION", "Plates", dark,
                    values: ["plateId": plate, "options": ["8", "3", "6", "看不出"], "plateNumber": 1, "plateCount": 8])
            }
            for kind in ["DRY_EYE", "PRESBYOPIA", "STRABISMUS", "COLOR_VISION"] {
                let report = sampleReport(kind)
                try await testShot("95-结果报告-" + kind, kind, kind == "STRABISMUS" || kind == "PRESBYOPIA" ? "RESULT" : "Result", dark,
                    values: ["report": report, "targetDistanceCm": 0, "canRest": kind == "DRY_EYE"], scroll: true)
            }
            var invalid = sampleReport("STRABISMUS")
            invalid["valid"] = false; invalid["title"] = "本次数据不足"; invalid["badge"] = "需要重测"
            invalid["problems"] = ["注视稳定度不足", "部分遮盖动作没有被相机确认"]
            try await testShot("96-结果报告-无效数据", "STRABISMUS", "RESULT", dark, values: ["report": invalid, "targetDistanceCm": 0], scroll: true)
            try await capture("97-本次数据明细", ResultReportView.captureData(sampleReport("PRESBYOPIA")), dark, navigation: true, scroll: true)
            try await capture("98-测试内设置", SystemSettingsPage(voice: EyeVoice(), feedback: EyeFeedback(), inTest: true), dark, navigation: true, scroll: true)
        }
        let data = try JSONSerialization.data(withJSONObject: ["platform": "iPhone Simulator", "system": UIDevice.current.systemVersion,
            "fixtureData": true, "screenshots": rows], options: [.prettyPrinted, .sortedKeys])
        try data.write(to: root.appendingPathComponent("截图清单.json"))
        XCTAssertTrue(rows.contains { ($0["page"] as? String)?.contains("太近了") == true })
        XCTAssertTrue(rows.contains { ($0["page"] as? String)?.contains("太远了") == true })
        XCTAssertGreaterThan(rows.count, 150)
    }

    private func testShot(_ name: String, _ kind: String, _ phase: String, _ dark: Bool,
                          values: [String: Any] = [:], calibrated: Bool = true, exitDialog: Bool = false, scroll: Bool = false) async throws {
        let runtime = fixture(kind, phase, values)
        try await capture(name, EyeTestView(captureKind: kind, snapshot: runtime.state, calibrated: calibrated, exitDialog: exitDialog), dark, scroll: scroll)
    }

    private func fixture(_ kind: String, _ phase: String, _ values: [String: Any] = [:]) -> EyeRuntime {
        let runtime = EyeRuntime(kind: kind)
        runtime.controller.selectTest(name: kind)
        let target = kind == "COLOR_VISION" ? 0.0 : kind == "STRABISMUS" ? 33.0 : 40.0
        runtime.state = ["phase": phase, "title": ["DRY_EYE":"干眼自测", "PRESBYOPIA":"老视自测", "STRABISMUS":"眼位自测", "COLOR_VISION":"色觉自测"][kind]!,
            "instruction": "保持头和手机稳定，按提示完成测试", "distanceCm": target, "targetDistanceCm": target, "distanceToleranceCm": 5.0,
            "faceDetected": true, "gateReady": true, "trialVisible": true, "trialId": 1, "direction": 0, "optotypePx": 42.0,
            "secondsLeft": 18, "naturalSeconds": 60, "overallProgress": 0.35, "remainingText": "剩余约 2 分钟",
            "eye": "RIGHT", "pushEye": "右眼", "pushStage": "MOVING", "targetX": 0.5, "targetY": 0.5,
            "calibrationX": 0.5, "calibrationY": 0.5, "calibrationCount": 9, "gazeCalibrationIndex": 0,
            "targetDiameterMm": 8, "farTargetM": 6, "subjectId": "capture-self", "subjectName": "示例用户"]
        // 与核心快照一致：选人、结果、休息和不采集距离的阶段不额外塞入距离条。
        if ["Profile", "PROFILE", "Result", "RESULT"].contains(phase) {
            runtime.state.removeValue(forKey: "overallProgress")
            runtime.state.removeValue(forKey: "subjectId")
            runtime.state["targetDistanceCm"] = 0
        }
        if kind == "DRY_EYE" && !["Setup", "FormalGuide", "Countdown", "Practice", "Natural", "Open"].contains(phase) {
            runtime.state["targetDistanceCm"] = 0
        }
        runtime.state.merge(values, uniquingKeysWith: { _, new in new })
        return runtime
    }

    private func capture<V: View>(_ name: String, _ view: V, _ dark: Bool, navigation: Bool = false, scroll: Bool = false) async throws {
        let theme = dark ? "深色" : "浅色"
        let directory = root.appendingPathComponent(theme, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let content = Group {
            if navigation { NavigationStack { view } } else { view }
        }.tint(EyeStyle.primary).foregroundStyle(EyeStyle.text)
            .background(EyeStyle.background.ignoresSafeArea()).scrollIndicators(.hidden)
            .environment(\.colorScheme, dark ? .dark : .light)
            .transaction { $0.disablesAnimations = true }
        let host = UIHostingController(rootView: content)
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.setNeedsLayout(); host.view.layoutIfNeeded()
        try await Task.sleep(nanoseconds: 450_000_000)
        if let scroller = scrollViews(host.view).filter({ $0.bounds.height > 200 }).max(by: { $0.contentSize.height < $1.contentSize.height }) {
            scroller.setContentOffset(CGPoint(x: 0, y: -scroller.adjustedContentInset.top), animated: false)
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        try save(name, theme, directory, offset: 0)
        if scroll, let scroller = scrollViews(host.view).filter({ $0.bounds.height > 200 && $0.contentSize.height > $0.bounds.height + 100 })
            .max(by: { $0.contentSize.height < $1.contentSize.height }) {
            let bottom = max(0, scroller.contentSize.height - scroller.bounds.height + scroller.adjustedContentInset.bottom)
            let step = scroller.bounds.height * 0.82
            var index = 1
            var y = min(step, bottom)
            while y > 0, index <= 4 {
                scroller.setContentOffset(CGPoint(x: 0, y: y), animated: false)
                try await Task.sleep(nanoseconds: 150_000_000)
                try save(name + "-下滚\(index)", theme, directory, offset: y)
                if y >= bottom { break }
                y = min(y + step, bottom); index += 1
            }
        }
    }

    private func save(_ name: String, _ theme: String, _ directory: URL, offset: CGFloat) throws {
        let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
        let image = renderer.image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
        let data = try XCTUnwrap(image.pngData())
        XCTAssertGreaterThan(data.count, 5000, "页面不能为空白：" + name)
        let filename = name + ".png"
        try data.write(to: directory.appendingPathComponent(filename))
        rows.append(["page": name, "theme": theme, "file": theme + "/" + filename,
                     "width": image.cgImage!.width, "height": image.cgImage!.height, "scrollOffset": Double(offset)])
    }

    private func scrollViews(_ view: UIView) -> [UIScrollView] {
        ((view as? UIScrollView).map { [$0] } ?? []) + view.subviews.flatMap { scrollViews($0) }
    }

    private func seedHome() {
        var report = CareReport()
        report.loaded = true; report.level = "LOW"; report.score = 92; report.label = "轻松"; report.mood = "HAPPY"
        report.headline = "今天保持着不错的用眼节奏"; report.checkedInToday = true; report.checkInDays = 5
        report.signals = [.init(id: "blink", group: "BASIC", label: "眨眼频率", value: "18 次/分", status: "NORMAL", note: ""),
                         .init(id: "distance", group: "BASIC", label: "平均距离", value: "39 cm", status: "NORMAL", note: "")]
        report.days = (0..<7).map { .init(id: "capture-day-\($0)", label: "\($0 + 5)日", blinksPerMinute: Double(15 + $0), score: 72 + $0 * 3) }
        EyeCareStore.shared.capture(report)
    }

    private func seedChat() {
        let messages: [[String: Any]] = [["role": "assistant", "text": "你好，我是爱科。最近看屏幕后，眼睛有什么感受？"],
            ["role": "user", "text": "下午用电脑久了，会有些干涩。"],
            ["role": "assistant", "text": "我记下了，我们再了解一下最近的用眼情况。",
             "question": ["prompt": "这种感受大约持续多久了？", "options": ["今天才有", "最近几天", "一周以上", "不太确定"]]]]
        CloudModel.shared.capture(state: ["configured": true, "signedIn": true, "email": "demo@example.com"], chat: ["state": "ready", "messages": messages, "sending": false])
    }

    private func sampleReport(_ kind: String) -> [String: Any] {
        let name = ["DRY_EYE":"干眼", "PRESBYOPIA":"老视", "STRABISMUS":"眼位", "COLOR_VISION":"色觉"][kind]!
        return ["valid": true, "level": "LOW", "badge": "演示结果", "title": name + "自测完成", "advice": "保持良好的用眼节奏，定期关注自己的眼睛感受。",
            "mood": "HAPPY", "footnote": "此处为截图演示数据。自测结果用于健康管理参考。", "version": "演示快照",
            "qualityNotes": ["本次示例展示数据完整的结果页面"],
            "metrics": [["label": "有效记录", "value": "完整", "status": "NORMAL"], ["label": "测试完成度", "value": "100%", "status": "NORMAL"]],
            "evidence": [["key": "capture", "title": "判断依据", "status": "NORMAL", "summary": "已完成本次示例流程", "details": ["距离稳定", "步骤完整"]]],
            "sections": [["title": "本次测试", "rows": [["label": "被测者", "value": "示例用户"], ["label": "项目", "value": name + "自测"], ["label": "数据来源", "value": "截图预置状态"]]]]]
    }
}
#endif
