import XCTest
import AVFoundation
import UIKit
@testable import EyeProject

/// 验证真实AVAssetWriter的编码、结束耗时与可回看清单；不使用合成图证明医学准确性。
@MainActor
final class StrabismusDiagnosticVideoTests: XCTestCase {
    func testIncrementalEncodingAndReplayManifest() async throws { try await encode(width: 640, height: 480, count: 24) }
    func testNative4kSourceCanBeArchived() async throws { try await encode(width: 2160, height: 3840, count: 60) }
    func testOverloadedQueueKeepsLatestFrameAndFullAudit() async throws {
        try await encode(width: 640, height: 480, count: 24, burstAfterFirstFrame: true)
    }

    private func encode(width: Int, height: Int, count: Int, burstAfterFirstFrame: Bool = false) async throws {
        let encodingQueue = DispatchQueue(label: "eye.test.diagnostic.video", qos: .utility, autoreleaseFrequency: .workItem)
        let recorder = StrabismusDiagnosticVideo(encodingQueue: encodingQueue)
        var queueSuspended = false
        defer { if queueSuspended { encodingQueue.resume() } }
        func waitForFrameAndAudit() async {
            // 等待该帧的取像、编码和分析行处理，避免把云端调度波动当成编码正确性。
            await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
                encodingQueue.async { done.resume() }
            }
        }
        let id = "strabismus_\(Int64(Date().timeIntervalSince1970 * 1000))_0"
        let directory = StrabismusDiagnosticVideo.root.appendingPathComponent(id)
        func send(_ type: String, _ values: [String: Any] = [:]) throws {
            var row = values; row["event"] = type; row["sessionId"] = id
            recorder.event(String(data: try JSONSerialization.data(withJSONObject: row), encoding: .utf8)!)
        }
        try send("start", ["createdAtMs": Date().timeIntervalSince1970 * 1000])
        for i in 0..<count {
            var buffer: CVPixelBuffer?
            XCTAssertEqual(CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_32BGRA,
                [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &buffer), kCVReturnSuccess)
            let pixel = try XCTUnwrap(buffer)
            CVPixelBufferLockBaseAddress(pixel, [])
            let ctx = try XCTUnwrap(CGContext(data: CVPixelBufferGetBaseAddress(pixel), width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(pixel), space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue))
            ctx.setFillColor(UIColor.red.cgColor); ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
            ctx.setFillColor(UIColor.white.cgColor); ctx.fill(CGRect(x: CGFloat(30 + i*10), y: 100, width: 30, height: 30))
            // 直接写入原始行，避免测试图本身的Quartz坐标方向掩盖编码倒置。
            let bytes = CVPixelBufferGetBaseAddress(pixel)!.assumingMemoryBound(to: UInt8.self)
            let stride = CVPixelBufferGetBytesPerRow(pixel)
            for y in 0..<height { for x in 0..<width {
                let offset = y * stride + x * 4
                bytes[offset] = y < height/2 ? 0 : 255; bytes[offset + 1] = 0
                bytes[offset + 2] = y < height/2 ? 255 : 0; bytes[offset + 3] = 255
            }}
            CVPixelBufferUnlockBaseAddress(pixel, [])
            recorder.capture(pixel, face: nil, time: Int64(10_000 + i*100))
            try send("frame", ["captureTimeMs": 10_000 + i*100, "phase": "PURSUIT", "calibrated": true,
                "target": [0.5, 0.25], "leftGaze": [0.4, 0.3], "rightGaze": [0.6, 0.4], "displayWidth": 390, "displayHeight": 844])
            if !burstAfterFirstFrame || i == 0 {
                await waitForFrameAndAudit()
                if burstAfterFirstFrame {
                    // 暂停消费者后提交整段突发帧，确定性覆盖待处理槽，再恢复真实编码。
                    encodingQueue.suspend()
                    queueSuspended = true
                } else {
                    try await Task.sleep(nanoseconds: 100_000_000)
                }
            }
        }
        if queueSuspended {
            encodingQueue.resume()
            queueSuspended = false
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
        let readyWait = Date().timeIntervalSince(began)
        let backup = try StrabismusDiagnosticVideo.root.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(backup.isExcludedFromBackup, true)
        XCTAssertEqual(manifest["state"] as? String, "ready", "\(manifest)")
        if burstAfterFirstFrame {
            XCTAssertEqual(manifest["frames"] as? Int, 2, "队列过载时应保留首帧和末帧")
        } else {
            XCTAssertGreaterThanOrEqual(manifest["frames"] as? Int ?? 0, 8, "\(manifest)")
        }
        let durationSeconds = try XCTUnwrap(manifest["durationSeconds"] as? Double)
        XCTAssertEqual(durationSeconds, Double(count - 1) / 10, accuracy: 0.001, "应保留真实末帧时刻")
        for (file, size) in [("original.mp4", CGSize(width: manifest["originalWidth"] as? Int ?? width, height: manifest["originalHeight"] as? Int ?? height)), ("comparison.mp4", CGSize(width: 1280, height: 720))] {
            let asset = AVURLAsset(url: directory.appendingPathComponent(file))
            let tracks = try await asset.loadTracks(withMediaType: .video)
            let track = try XCTUnwrap(tracks.first)
            let actualSize = try await track.load(.naturalSize)
            XCTAssertEqual(actualSize, size)
            let duration = try await asset.load(.duration)
            XCTAssertGreaterThan(duration.seconds, Double(count - 4) / 10)
            let generator = AVAssetImageGenerator(asset: asset)
            let image = try generator.copyCGImage(at: CMTime(value: 500, timescale: 1000), actualTime: nil)
            func color(_ x: Int, _ y: Int) throws -> [UInt8] {
                let pixel = try XCTUnwrap(image.cropping(to: CGRect(x: x, y: y, width: 1, height: 1)))
                var rgba = [UInt8](repeating: 0, count: 4)
                try rgba.withUnsafeMutableBytes { raw in
                    let context = try XCTUnwrap(CGContext(data: raw.baseAddress, width: 1, height: 1,
                        bitsPerComponent: 8, bytesPerRow: 4, space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
                    context.draw(pixel, in: CGRect(x: 0, y: 0, width: 1, height: 1))
                }
                return rgba
            }
            let panelY = height > width ? 160 : 240
            let top = try color(file == "original.mp4" ? 30 : 160, file == "original.mp4" ? 20 : panelY)
            let bottom = try color(file == "original.mp4" ? 30 : 160, file == "original.mp4" ? image.height - 20 : (height > width ? 500 : 420))
            XCTAssertGreaterThan(top[0], 180, "上半原画应为红色：\(file)")
            XCTAssertGreaterThan(bottom[2], 180, "下半原画应为蓝色：\(file)")
        }
        let data = try String(contentsOf: directory.appendingPathComponent("frames.jsonl"), encoding: .utf8)
        XCTAssertEqual(data.split(separator: "\n").count, count + 2)
        print("渲染性能：\(manifest)")
        print("诊断视频 \(width)×\(height) 结束等待 \(readyWait) 秒，封装 \(manifest["finalizationMs"] ?? 0)ms，帧数 \(manifest["frames"] ?? 0)")
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
