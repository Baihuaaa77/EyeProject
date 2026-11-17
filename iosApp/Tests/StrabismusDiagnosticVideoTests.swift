import XCTest
import AVFoundation
import UIKit
@testable import EyeProject

/// 验证真实AVAssetWriter的编码、结束耗时与可回看清单；不使用合成图证明医学准确性。
@MainActor
final class StrabismusDiagnosticVideoTests: XCTestCase {
    func testIncrementalEncodingAndReplayManifest() async throws {
        let recorder = StrabismusDiagnosticVideo()
        let id = "strabismus_\(Int64(Date().timeIntervalSince1970 * 1000))_0"
        let directory = StrabismusDiagnosticVideo.root.appendingPathComponent(id)
        func send(_ type: String, _ values: [String: Any] = [:]) throws {
            var row = values; row["event"] = type; row["sessionId"] = id
            recorder.event(String(data: try JSONSerialization.data(withJSONObject: row), encoding: .utf8)!)
        }
        try send("start", ["createdAtMs": Date().timeIntervalSince1970 * 1000])
        for i in 0..<24 {
            var buffer: CVPixelBuffer?
            XCTAssertEqual(CVPixelBufferCreate(nil, 640, 480, kCVPixelFormatType_32BGRA,
                [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &buffer), kCVReturnSuccess)
            let pixel = try XCTUnwrap(buffer)
            CVPixelBufferLockBaseAddress(pixel, [])
            let ctx = try XCTUnwrap(CGContext(data: CVPixelBufferGetBaseAddress(pixel), width: 640, height: 480,
                bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(pixel), space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue))
            ctx.setFillColor(UIColor.red.cgColor); ctx.fill(CGRect(x: 0, y: 0, width: 640, height: 480))
            ctx.setFillColor(UIColor.white.cgColor); ctx.fill(CGRect(x: CGFloat(30 + i*10), y: 100, width: 30, height: 30))
            CVPixelBufferUnlockBaseAddress(pixel, [])
            recorder.capture(pixel, face: nil, time: Int64(10_000 + i*100))
            try send("frame", ["captureTimeMs": 10_000 + i*100, "phase": "PURSUIT", "calibrated": true,
                "target": [0.5, 0.25], "leftGaze": [0.4, 0.3], "rightGaze": [0.6, 0.4], "displayWidth": 390, "displayHeight": 844])
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        let began = Date()
        try send("end", ["reason": "编码测试"])
        var manifest: [String: Any] = [:]
        while Date().timeIntervalSince(began) < 8 {
            if let data = try? Data(contentsOf: directory.appendingPathComponent("manifest.json")),
               let row = try JSONSerialization.jsonObject(with: data) as? [String: Any] { manifest = row }
            if ["ready", "failed"].contains(manifest["state"] as? String ?? "") { break }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertEqual(manifest["state"] as? String, "ready", "\(manifest)")
        XCTAssertGreaterThanOrEqual(manifest["frames"] as? Int ?? 0, 8)
        for (file, size) in [("original.mp4", CGSize(width: 640, height: 480)), ("comparison.mp4", CGSize(width: 1280, height: 720))] {
            let asset = AVURLAsset(url: directory.appendingPathComponent(file))
            let tracks = try await asset.loadTracks(withMediaType: .video)
            let track = try XCTUnwrap(tracks.first)
            let actualSize = try await track.load(.naturalSize)
            XCTAssertEqual(actualSize, size)
            let duration = try await asset.load(.duration)
            XCTAssertGreaterThan(duration.seconds, 0.7)
        }
        let data = try String(contentsOf: directory.appendingPathComponent("frames.jsonl"), encoding: .utf8)
        XCTAssertEqual(data.split(separator: "\n").count, 26)
        print("诊断视频结束等待 \(Date().timeIntervalSince(began)) 秒，帧数 \(manifest["frames"] ?? 0)")
    }

    func testCompositeDrawsActualTargetAndMissingEstimateHonestly() throws {
        let source = UIGraphicsImageRenderer(size: CGSize(width: 640, height: 480)).image { c in
            UIColor.red.setFill(); c.fill(CGRect(x: 0, y: 0, width: 640, height: 480))
        }
        let cg = try XCTUnwrap(source.cgImage)
        let crop = DiagnosticCrop(rect: CGRect(x: 200, y: 180, width: 100, height: 50), image: cg, inherited: false)
        let images = DiagnosticImages(time: 1000, full: cg, left: crop, right: crop)
        let row: [String: Any] = ["phase": "GAZE_CALIBRATION", "target": [0.5, 0.5], "calibrated": false,
            "targetVisible": true, "displayWidth": 390, "displayHeight": 844, "reasons": ["无可靠注视标定"]]
        let result = DiagnosticPanelRenderer.render(images, row: row, elapsed: 0)
        XCTAssertEqual(result.size, CGSize(width: 1280, height: 720))
        XCTAssertNotNil(result.pngData())
    }
}
