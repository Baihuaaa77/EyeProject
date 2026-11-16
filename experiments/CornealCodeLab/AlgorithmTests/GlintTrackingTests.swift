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
    func testHistoricalUnresolvedPatternTracksWholeBrightBlobRatherThanTinyCenter() throws {
        var c = TrialConfiguration.pointDefault
        c.mode = .patternCoded; c.dotDiameter = 192
        var record = row(0)
        record.distanceCM = 33; record.nativeOffset = 0; record.nativeROI = roi
        let pixels = Data(image(x: 64,y: 38).map { UInt8(max(0,min(255,$0))) })
        let tracks = NativeGlintTracking.track(records: [record],pixels: pixels,bytesPerPixel: 1,configuration: c,events: [],start: 0)
        let frame = try XCTUnwrap(tracks.first?.frames.first)
        let whole = try XCTUnwrap(OpticalPrediction.diameter(distanceCM: 33,irisPixels: 72,sourceMM: 65*192/390))
        XCTAssertEqual(try XCTUnwrap(frame.expectedDiameterPixels),whole,accuracy: 1e-9)
        XCTAssertTrue(frame.source.measured)
        XCTAssertLessThan(hypot(frame.imagePoint!.x-64,frame.imagePoint!.y-38),1.5)
    }
    func testKnownOffKeepsIndependentCandidateWithoutUpdatingObservation() throws {
        var independent = GlintTracker(),conditioned = GlintTracker()
        for i in 0..<5 {
            let pixels = image(x: 64,y: 38)
            _ = independent.update(pixels: pixels,width: 128,height: 96,roi: roi,iris: iris,row: row(i),expectedDiameter: 3)
            _ = conditioned.update(pixels: pixels,width: 128,height: 96,roi: roi,iris: iris,row: row(i),expectedDiameter: 3)
        }
        // 故意把强环境反光放在原光点附近：独立图像仍可能接受，已知灭灯时不得更新观测状态。
        let pixels = image(x: 65,y: 38)
        let raw = independent.update(pixels: pixels,width: 128,height: 96,roi: roi,iris: iris,row: row(5),expectedDiameter: 3)
        let gated = conditioned.update(pixels: pixels,width: 128,height: 96,roi: roi,iris: iris,row: row(5),expectedDiameter: 3,measurementAllowed: false)
        XCTAssertTrue(raw.source.measured)
        XCTAssertNotNil(gated.candidatePoint)
        XCTAssertFalse(gated.source.measured); XCTAssertNil(gated.measurementPoint)
        let on = conditioned.update(pixels: image(x: 64,y: 38),width: 128,height: 96,roi: roi,iris: iris,row: row(6),expectedDiameter: 3)
        XCTAssertTrue(on.source.measured,"下一亮段仍应找回原光点")
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
    func testTemporalSupportNeedsCurrentPixelEvidenceAndBothDirections() {
        func sample(_ i: Int) -> GlintEstimate {
            GlintEstimate(frameIndex: i,pts: Double(i)/30,hostTime: Double(i)/30,imagePoint: LabPoint(x: 64,y: 38),
                measurementPoint: LabPoint(x: 64,y: 38),irisRelative: LabPoint(x: 0,y: -0.28),confidence: 0.7,source: .observed,uncertaintyPixels: 1,snr: 5,ambiguity: 0.2,
                candidatePoint: LabPoint(x: 64,y: 38),candidateConfidence: 0.2,emissionPhase: .on,candidateIrisRelative: LabPoint(x: 0,y: -0.28))
        }
        var frames = (0..<5).map(sample)
        frames[2].source = .predicted; frames[2].measurementPoint = nil
        let supported = TemporalGlintSupport.refine(frames)
        XCTAssertEqual(supported[2].source,.weak); XCTAssertEqual(supported[2].temporalSupportFrames,4)
        frames[2].snr = 0.5
        XCTAssertEqual(TemporalGlintSupport.refine(frames)[2].source,.predicted,"不能把空白帧补成检出")
        frames[2].snr = 5; frames[2].emissionPhase = .off
        XCTAssertEqual(TemporalGlintSupport.refine(frames)[2].source,.predicted,"灭灯不能被时序支持提升为测量")
    }
}
