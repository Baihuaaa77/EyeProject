import XCTest
import SwiftUI
import AVFoundation
@testable import CornealCodeLab

final class InteractiveMediaTests: XCTestCase {
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
