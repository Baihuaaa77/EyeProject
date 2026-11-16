import XCTest
@testable import CornealAlgorithms

final class GlintTrackingTests: XCTestCase {
    private let roi = LabRect(x: 0,y: 0,width: 128,height: 96)
    private var iris: IrisRegion {
        IrisRegion(center: LabPoint(x: 0.5,y: 0.5),radiusX: 36.0/128,radiusY: 36.0/96,confidence: 1,
            eyelid: [LabPoint(x: 0,y: 0),LabPoint(x: 1,y: 0),LabPoint(x: 1,y: 1),LabPoint(x: 0,y: 1)])
    }
    private func row(_ index: Int) -> FrameRecord {
        FrameRecord(index: index,pts: Double(index)/30,hostTime: Double(index)/30,exposureSeconds: 0.01,iso: 100,
            width: 128,height: 96,status: "valid",eye: "image_left",roi: roi,iris: iris)
    }
    private func image(x: Double,y: Double,bright: Bool = true,distractor: Bool = false) -> [Float] {
        var pixels = [Float](repeating: 0,count: 128*96)
        for i in pixels.indices {
            let px = Double(i%128)+0.5,py = Double(i/128)+0.5
            let texture: Double = 3*sin(px/3)+2*cos(py/4)
            let dx = px-x,dy = py-y
            let dot: Double = bright ? 140*exp(-(dx*dx+dy*dy)/3) : 0
            let ox = dx-15,oy = dy-12
            let other: Double = distractor ? 200*exp(-(ox*ox+oy*oy)/3) : 0
            pixels[i] = Float(30+texture+dot+other)
        }
        return pixels
    }
    func testSmallNativeSpotSurvivesHandheldTranslationAndScale() throws {
        var tracker = GlintTracker()
        for i in 0..<30 {
            let dx = sin(Double(i)*0.2)*4,dy = cos(Double(i)*0.2)*3
            var region = iris; region.center = LabPoint(x: (64+dx)/128,y: (48+dy)/96)
            let expected = LabPoint(x: 64+dx,y: 38+dy)
            let f = tracker.update(pixels: image(x: expected.x,y: expected.y),width: 128,height: 96,roi: roi,iris: region,row: row(i),expectedDiameter: 3)
            let point = try XCTUnwrap(f.imagePoint)
            XCTAssertLessThan(hypot(point.x-expected.x,point.y-expected.y),1.5)
            XCTAssertTrue(f.source.measured)
        }
    }
    func testDarkFrameDoesNotSwitchToBrighterEnvironmentReflection() throws {
        var tracker = GlintTracker()
        for i in 0..<5 { _ = tracker.update(pixels: image(x: 64,y: 38),width: 128,height: 96,roi: roi,iris: iris,row: row(i),expectedDiameter: 3) }
        for i in 5..<12 {
            let f = tracker.update(pixels: image(x: 64,y: 38,bright: false,distractor: true),width: 128,height: 96,roi: roi,iris: iris,row: row(i),expectedDiameter: 3)
            XCTAssertFalse(f.source.measured)
            XCTAssertLessThan(hypot(f.imagePoint!.x-64,f.imagePoint!.y-38),4)
        }
    }
    func testFlatFrameCannotClaimAnObservation() {
        var tracker = GlintTracker()
        let f = tracker.update(pixels: [Float](repeating: 40,count: 128*96),width: 128,height: 96,roi: roi,iris: iris,row: row(0),expectedDiameter: 2)
        XCTAssertEqual(f.source,.unavailable); XCTAssertEqual(f.confidence,0)
    }
    func testEveryDeliveredFrameIsPreservedAndNoEyeCannotInventEvidence() {
        let rows = (0..<12).map(row)
        let completed = NativeGlintTracking.complete([],records: rows+rows)
        XCTAssertEqual(completed.count,12)
        XCTAssertTrue(completed.allSatisfy { $0.source == .unavailable && $0.confidence == 0 && $0.imagePoint == nil })
    }
    func testDisagreeingDirectionsArePredictionsWithCappedConfidence() {
        var tracker = GlintTracker()
        let a = tracker.update(pixels: image(x: 64,y: 38),width: 128,height: 96,roi: roi,iris: iris,row: row(0),expectedDiameter: 3)
        var b = a; b.imagePoint!.x += 15; b.confidence = 0.95
        let joint = NativeGlintTracking.fuse(a,b)
        XCTAssertEqual(joint.source,.predicted)
        XCTAssertLessThanOrEqual(joint.confidence,0.25)
        XCTAssertNil(joint.measurementPoint)
    }
    func testFusedPixelAndRelativeCoordinatesUseTheSameWeights() {
        var tracker = GlintTracker()
        let a = tracker.update(pixels: image(x: 64,y: 38),width: 128,height: 96,roi: roi,iris: iris,row: row(0),expectedDiameter: 3)
        var b = a; b.imagePoint!.x += 2; b.irisRelative!.x += 2/36.0; b.confidence *= 0.8
        let joint = NativeGlintTracking.fuse(a,b)
        XCTAssertEqual(joint.irisRelative!.x,(joint.imagePoint!.x-64)/36,accuracy: 1e-9)
    }
}
