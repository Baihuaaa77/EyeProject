import XCTest
import SwiftUI
import AVFoundation
@testable import CornealCodeLab

final class InteractiveMediaTests: XCTestCase {
    @MainActor func testBrightnessWriteReadbackMonitoringAndRestore() {
        var actual = 0.4
        let brightness = ScreenBrightnessController(read: { actual },write: { value in actual = value },monitor: false)
        brightness.apply(1)
        XCTAssertEqual(actual,1,accuracy: 0.001); XCTAssertTrue(brightness.confirmed)
        brightness.beginCapture(); actual = 0.65; brightness.refresh(reason: "手动调整")
        XCTAssertFalse(brightness.confirmed)
        let log = brightness.endCapture()
        XCTAssertGreaterThanOrEqual(log.count,2); XCTAssertEqual(log.last!.actual,0.65,accuracy: 0.001)
        brightness.releaseControl(); XCTAssertEqual(actual,0.4,accuracy: 0.001)
    }
    @MainActor func testRejectedBrightnessWriteCannotClaimConfirmation() {
        let brightness = ScreenBrightnessController(read: { 0.4 },write: { _ in },monitor: false)
        brightness.apply(1)
        XCTAssertFalse(brightness.confirmed); XCTAssertEqual(brightness.actual,0.4,accuracy: 0.001)
    }
    @MainActor func testStartingWithFocusedInputDismissesKeyboardBeforeCameraGuard() {
        let window: UIWindow
        if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
            window = UIWindow(windowScene: scene)
            window.frame = CGRect(x: 0,y: 0,width: 375,height: 812)
        } else { window = UIWindow(frame: CGRect(x: 0,y: 0,width: 375,height: 812)) }
        let controller = UIViewController(); window.rootViewController = controller; window.makeKeyAndVisible()
        let field = UITextField(frame: CGRect(x: 20,y: 100,width: 200,height: 44)); field.keyboardType = .decimalPad
        controller.view.addSubview(field); field.becomeFirstResponder()
        XCTAssertTrue(field.isFirstResponder,"先确认键盘焦点已建立")
        let model = LabModel(); model.begin()
        XCTAssertFalse(field.isFirstResponder)
        window.isHidden = true
    }

    @MainActor func testPointControlsRenderAtSmallWidthAndLargeType() {
        for category in [DynamicTypeSize.large,.accessibility3] {
            let model = LabModel()
            let content = NavigationStack {
                Form { CaptureConditionFields(model: model) }.scrollDismissesKeyboard(.interactively)
            }.environment(\.dynamicTypeSize,category).preferredColorScheme(.dark)
            let controller = UIHostingController(rootView: content)
            let window = UIWindow(frame: CGRect(x: 0,y: 0,width: 375,height: 812))
            window.rootViewController = controller; window.makeKeyAndVisible()
            controller.view.frame = window.bounds; controller.view.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(size: window.bounds.size).image { context in controller.view.layer.render(in: context.cgContext) }
            let attachment = XCTAttachment(image: image); attachment.name = "白点参数-\(category)"; attachment.lifetime = XCTAttachment.Lifetime.keepAlways; add(attachment)
            XCTAssertEqual(image.size.width,375)
            window.isHidden = true
        }
    }

    func testInactivePreviewDoesNotReportACaptureFailure() {
        let camera = LabCamera()
        let error = expectation(description: "未开始采集时不显示相机故障"); error.isInverted = true
        camera.onError = { _ in error.fulfill() }
        NotificationCenter.default.post(name: AVCaptureSession.runtimeErrorNotification,object: camera.captureSession)
        NotificationCenter.default.post(name: AVCaptureSession.wasInterruptedNotification,object: camera.captureSession)
        wait(for: [error],timeout: 0.2)
    }

    @MainActor func testZoomAndOverlayKeepThePlayerItemAndPlaybackClock() throws {
        let player = AVPlayer()
        let item = AVPlayerItem(url: URL(fileURLWithPath: "/tmp/不存在的视频.mov"))
        player.replaceCurrentItem(with: item)
        let view = VideoZoomScroll(player: player)
        view.frame = CGRect(x: 0,y: 0,width: 375,height: 340); view.layoutIfNeeded()
        let originalLayer = view.canvas.playerLayer
        view.setZoomScale(3,animated: false)
        view.canvas.marks.isHidden = false
        view.setZoomScale(1.5,animated: false)
        XCTAssertTrue(player.currentItem === item)
        XCTAssertTrue(view.canvas.playerLayer === originalLayer)
        XCTAssertTrue(view.canvas.playerLayer.player === player)
        XCTAssertEqual(view.zoomScale,1.5,accuracy: 0.01)
        XCTAssertEqual(CMTimeGetSeconds(player.currentTime()),0)
        view.detach()
    }

    @MainActor func testOpticalEvidenceRendersAtSmallWidthAndLargeType() throws {
        let prediction = OpticalPrediction(distanceCM: 33,distanceMinCM: 32,distanceMaxCM: 34,distanceSource: "相机帧内参＋共享虹膜测距",irisDiameterPixels: 40,
            screenWidthMM: 65,screenHeightMM: 140,screenSizeSource: "原生分辨率规格映射",cornealRadiusMM: 7.8,stimulusDiameterMM: 10,
            reflectedScreenWidthPixels: 2.6,reflectedScreenHeightPixels: 5.5,expectedDiameterPixels: 0.4,expectedMinPixels: 0.3,expectedMaxPixels: 0.5,
            observedDiameterPixels: 2,observedToExpected: 5,distanceSamples: 300,note: "曲率采用群体假设；像素预算用于核查。")
        for category in [DynamicTypeSize.large,.accessibility3] {
            let content = ScrollView { OpticalEvidenceView(prediction: prediction).padding() }.environment(\.dynamicTypeSize,category).preferredColorScheme(.dark)
            let controller = UIHostingController(rootView: content)
            let window = UIWindow(frame: CGRect(x: 0,y: 0,width: 375,height: 812)); window.rootViewController = controller; window.makeKeyAndVisible()
            controller.view.frame = window.bounds; controller.view.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(size: window.bounds.size).image { graphics in controller.view.layer.render(in: graphics.cgContext) }
            XCTAssertEqual(image.size.width,375)
            let attachment = XCTAttachment(image: image); attachment.name = "光学核查-\(category)"; attachment.lifetime = .keepAlways; add(attachment)
            window.isHidden = true
        }
    }
}
