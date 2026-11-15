import XCTest
@testable import CornealAlgorithms

final class ReplayTests: XCTestCase {
    func testRoundTripFromRawFilesAndNoOverwrite() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("corneal-test-"+UUID().uuidString,isDirectory: true)
        try FileManager.default.createDirectory(at: directory,withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let iris = IrisRegion(center: LabPoint(x: 0.5,y: 0.5),radiusX: 28.0/128,radiusY: 28.0/64,confidence: 1,
            eyelid: [LabPoint(x: 0.1,y: 0.02),LabPoint(x: 0.9,y: 0.02),LabPoint(x: 0.9,y: 0.98),LabPoint(x: 0.1,y: 0.98)])
        let pixels: [Float] = (0..<8192).map { i in
            let x = Double(i%128)+0.5-64,y = Double(i/128)+0.5-32
            return abs(x)<8 && abs(y)<16 && hypot(x,y+8)>=2 ? 180 : 30
        }
        var c = TrialConfiguration(); c.mode = .screenTarget; c.dotDiameter = 96; c.displayHeight = 780
        let encoder = JSONEncoder()
        struct Header: Encodable { var configuration: TrialConfiguration; var startHostTime: Double }
        try encoder.encode(Header(configuration: c,startHostTime: 0)).write(to: directory.appendingPathComponent("summary.json"))
        try Data("[]".utf8).write(to: directory.appendingPathComponent("display_events.json"))
        var rows: [String] = [],binary = Data()
        for i in 0..<80 {
            let s = PatchSample(eye: "image_left",hostTime: Double(i)*0.4,pixels: pixels,roi: LabRect(x: 0,y: 0,width: 128,height: 64),pupil: LabPoint(x: 64,y: 32),rawCandidate: nil,saturation: 0,iris: iris)
            let r = FrameRecord(index: i,pts: s.hostTime,hostTime: s.hostTime,exposureSeconds: 0.01,iso: 100,width: 128,height: 64,status: "valid",
                eye: s.eye,roi: s.roi,pupil: s.pupil,rawCandidate: nil,saturation: 0,patchOffset: binary.count,iris: s.iris,nuisance: s.nuisance)
            rows.append(String(decoding: try encoder.encode(r),as: UTF8.self))
            let bits = s.pixels.map { $0.bitPattern.littleEndian }
            bits.withUnsafeBytes { binary.append(contentsOf: $0) }
        }
        // Windows工具常将JSONL转成CRLF，需按换行字符处理而非只匹配单个LF。
        try rows.joined(separator: "\r\n").write(to: directory.appendingPathComponent("frames.jsonl"),atomically: true,encoding: .utf8)
        try binary.write(to: directory.appendingPathComponent("patches.f32"))
        let output = directory.appendingPathComponent("replayed.json")
        try CornealReplay.run(directory: directory,output: output)
        let results = try JSONDecoder().decode([EyeAnalysis].self,from: Data(contentsOf: output))
        XCTAssertTrue(results[0].screenResult!.structuralEvidence)
        XCTAssertEqual(results[1].samples,0)
        XCTAssertThrowsError(try CornealReplay.run(directory: directory,output: output))
        XCTAssertThrowsError(try CornealReplay.run(directory: directory,output: directory.appendingPathComponent("summary.json")))
        try binary.prefix(20).write(to: directory.appendingPathComponent("patches.f32"))
        XCTAssertThrowsError(try CornealReplay.run(directory: directory,output: directory.appendingPathComponent("broken.json")))
    }
}
