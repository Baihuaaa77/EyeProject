import XCTest
@testable import CornealAlgorithms

final class OpticalExportTests: XCTestCase {
    func testConvexMirrorBudgetScalesAndRejectsInvalidInput() throws {
        let a = try XCTUnwrap(OpticalPrediction.diameter(distanceCM: 33,irisPixels: 40,sourceMM: 10))
        let b = try XCTUnwrap(OpticalPrediction.diameter(distanceCM: 33,irisPixels: 80,sourceMM: 10))
        XCTAssertEqual(b,a*2,accuracy: 1e-12)
        XCTAssertEqual(a,10*3.9/333.9*40/11.7,accuracy: 1e-12)
        XCTAssertGreaterThan(OpticalPrediction.diameter(distanceCM: 25,irisPixels: 40,sourceMM: 10)!,a)
        XCTAssertNil(OpticalPrediction.diameter(distanceCM: .nan,irisPixels: 40,sourceMM: 10))
        XCTAssertNil(OpticalPrediction.diameter(distanceCM: 0,irisPixels: 40,sourceMM: 10))
        XCTAssertEqual(OpticalPrediction.median([10,30,20,40]),25)
    }
    func testNewConfigAndHistoricalConfigKeepDistanceProvenance() throws {
        XCTAssertTrue(TrialConfiguration().automaticDistance)
        let old = try JSONDecoder().decode(TrialConfiguration.self,from: Data("{}".utf8))
        XCTAssertFalse(old.automaticDistance)
        XCTAssertEqual(old.screenSizeSource,"旧记录的屏幕尺寸假设")
    }
    func testFullIrisMaskIncludesPeripheralPointButStillExcludesLidAndOutsideIris() {
        let eyelid = [LabPoint(x: 0.1,y: 0.1),LabPoint(x: 0.9,y: 0.1),LabPoint(x: 0.9,y: 0.9),LabPoint(x: 0.1,y: 0.9)]
        let iris = IrisRegion(center: LabPoint(x: 0.5,y: 0.5),radiusX: 0.3,radiusY: 0.4,confidence: 1,eyelid: eyelid)
        XCTAssertTrue(iris.contains(LabPoint(x: 0.77,y: 0.5)))
        XCTAssertFalse(iris.contains(LabPoint(x: 0.81,y: 0.5)))
        XCTAssertFalse(iris.contains(LabPoint(x: 0.5,y: 0.91)))
    }
    func testSingleFileBundlePreservesJSONLinesAndBinaryAcrossChunkBoundary() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString,isDirectory: true)
        try FileManager.default.createDirectory(at: directory,withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try Data("{\"id\":\"中文实验\"}".utf8).write(to: directory.appendingPathComponent("summary.json"))
        try Data("{\"index\":1}\n{\"index\":2}\n".utf8).write(to: directory.appendingPathComponent("frames.jsonl"))
        let bytes = Data((0..<(3*256*1024+11)).map { UInt8($0%251) })
        try bytes.write(to: directory.appendingPathComponent("patches.f32"))
        let output = try TrialDataExport.write(directory: directory)
        let bundle = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: output)) as? [String:Any])
        let documents = try XCTUnwrap(bundle["documents"] as? [String:Any])
        XCTAssertEqual((documents["frames.jsonl"] as? [[String:Int]])?.count,2)
        let binaries = try XCTUnwrap(bundle["binaryFiles"] as? [String:String])
        XCTAssertEqual(Data(base64Encoded: binaries["patches.f32"]!),bytes)
        let lite = try TrialDataExport.write(directory: directory,includePixels: false)
        let light = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: lite)) as? [String:Any])
        XCTAssertEqual((light["binaryFiles"] as? [String:String])?.count,0)
        let repeated = try TrialDataExport.write(directory: directory)
        let again = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: repeated)) as? [String:Any])
        XCTAssertEqual((again["documents"] as? [String:Any])?.count,2,"重复导出不能递归嵌入前一次合并文件")
    }
}
