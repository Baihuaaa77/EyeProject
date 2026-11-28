import EyeScreening
import XCTest
@testable import StrabismusLab

final class StrabismusLabSmokeTests: XCTestCase {
    func testResearchRadiusDefaultIs080() {
        XCTAssertEqual(ScreenPointOptics.shared.RESEARCH_SEARCH_RADIUS, 0.80, accuracy: 0.0001)
        let cfg = LabProtocolConfig(
            subjectId: "IOS-TEST",
            glassesState: "裸眼",
            lightingCondition: "室内",
            clinicalNote: "",
            codingScheme: .mSequence,
            searchRadiusFraction: 0.80,
            chipDurationMs: 250,
            stimulusDiameterMm: 34.0,
            nearDurationSec: 16,
            farDurationSec: 12,
            pursuitDurationSec: 18,
            includeNear: true,
            includeFar: true,
            includeCover: true,
            includePursuit: true,
            lightOffsetXMm: 0.0,
            lightOffsetYMm: 22.0
        )
        XCTAssertEqual(cfg.searchRadiusFraction, 0.80, accuracy: 0.0001)
        XCTAssertEqual(cfg.activeStages.count, 4)
    }

    func testDatasetDistanceSupervisorArmingAndAbort() {
        var sup = DatasetDistanceSupervisor(targetCm: 25.0, toleranceCm: 2.0, armHoldMs: 600, maxOutOfRangeFrames: 2)

        // 超出 ±2 cm 时不能开始
        let far = sup.evaluateArming(faceDetected: true, rawDistanceCm: 28.2, nowMs: 1000)
        if case .waiting(_, let inRange, let progress, _) = far {
            XCTAssertFalse(inRange)
            XCTAssertEqual(progress, 0.0, accuracy: 0.001)
        } else {
            XCTFail("距离越界时不应允许开始")
        }

        // 进入 25±2 cm 并稳定保持 >= 600 ms 后自动放行
        _ = sup.evaluateArming(faceDetected: true, rawDistanceCm: 25.4, nowMs: 2000)
        _ = sup.evaluateArming(faceDetected: true, rawDistanceCm: 25.2, nowMs: 2300)
        let ready = sup.evaluateArming(faceDetected: true, rawDistanceCm: 25.1, nowMs: 2650)
        if case .readyToStart(let dist) = ready {
            XCTAssertTrue(abs(dist - 25.0) <= 2.0)
        } else {
            XCTFail("稳定 600ms 后应自动进入录制")
        }

        // 采集中单帧毛刺不应误中断，连续偏离超过 2 cm 必须触发中断重来
        sup.beginRecordingWindow()
        let singleSpike = sup.evaluateRecording(faceDetected: true, rawDistanceCm: 27.6)
        if case .abort = singleSpike {
            XCTFail("单帧轻微跳动不应立即误中断")
        }
        _ = sup.evaluateRecording(faceDetected: true, rawDistanceCm: 27.8)
        let aborted = sup.evaluateRecording(faceDetected: true, rawDistanceCm: 28.0)
        if case .abort = aborted {
            // expected
        } else {
            XCTFail("采集中连续偏离超过 2 cm 必须触发中断重来")
        }
    }

    func testDatasetZipArchiveProducesValidZipHeader() throws {
        let fm = FileManager.default
        let tmp = fm.temporaryDirectory.appendingPathComponent("StrabismusZipTest_\(UUID().uuidString)", isDirectory: true)
        let sampleDir = tmp.appendingPathComponent("SUBJ-001_25cm_bare", isDirectory: true)
        try fm.createDirectory(at: sampleDir, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: tmp) }

        try "{\"ok\":true}\n".write(to: sampleDir.appendingPathComponent("dataset_meta.json"), atomically: true, encoding: .utf8)
        let zipURL = tmp.appendingPathComponent("out.zip")
        let entries = StrabismusDatasetArchive.collectEntries(from: [sampleDir], rootFolderName: "StrabismusDataset")
        try StrabismusDatasetArchive.writeZip(entries: entries, to: zipURL)

        let data = try Data(contentsOf: zipURL)
        XCTAssertTrue(data.count > 40)
        // ZIP Local File Header signature: PK\x03\x04
        XCTAssertEqual(Array(data.prefix(4)), [0x50, 0x4B, 0x03, 0x04])
    }
}
