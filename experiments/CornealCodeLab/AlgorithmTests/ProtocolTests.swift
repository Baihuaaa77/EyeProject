import XCTest
@testable import CornealAlgorithms

final class ProtocolTests: XCTestCase {
    func testFastProtocolStays32SecondsAnd20msCannotRunAt60FPS() {
        var c = TrialConfiguration(); c.chipSeconds = 0.1; c.chipCount = 320; c.transitionGuardMS = 40
        XCTAssertEqual(c.duration,32); XCTAssertNil(c.validationError)
        c.captureFPS = 60; XCTAssertNotNil(c.validationError)
        c.exposureMS = 10; XCTAssertNil(c.validationError)
        XCTAssertEqual(StimulusCode.value(at: 6.45,configuration: c),StimulusCode.bits[0])
    }
    func testPlanRejectsDuplicateOrderAndInvalidExposure() throws {
        let data = Data("{\"protocol\":\"screen\",\"seed\":1,\"blocks\":1,\"rounds\":[{\"order\":1,\"block\":1,\"configuration\":{\"mode\":\"screenTarget\"}}]}".utf8)
        var plan = try JSONDecoder().decode(ExperimentPlan.self,from: data)
        XCTAssertNoThrow(try plan.validate())
        plan.rounds.append(plan.rounds[0]); XCTAssertThrowsError(try plan.validate())
        plan.rounds.removeLast(); plan.rounds[0].configuration.captureFPS = 60
        XCTAssertThrowsError(try plan.validate())
    }
    func testLockInReportsSubpixelAndAll63Shifts() {
        var c = TrialConfiguration(); c.mode = .coded
        let iris = IrisRegion(center: LabPoint(x: 0.5,y: 0.5),radiusX: 0.2,radiusY: 0.4,confidence: 1,
            eyelid: [LabPoint(x: 0.1,y: 0.1),LabPoint(x: 0.9,y: 0.1),LabPoint(x: 0.9,y: 0.9),LabPoint(x: 0.1,y: 0.9)])
        let events = (0..<64).map { i in DisplayEvent(hostTime: Double(i)*0.5,intensity: 0.5,code: StimulusCode.bits[i],chip: i,callbackHostTime: Double(i)*0.5) }
        let samples = (0..<160).map { i -> PatchSample in
            let t = Double(i)/5,bit = StimulusCode.value(at: t-0.1,configuration: c)
            var pixels = [Float](repeating: 40,count: 8192)
            for p in pixels.indices {
                let dx = Double(p%128)+0.5-64.3,dy = Double(p/128)+0.5-32.2
                pixels[p] += Float((100+30*bit)*exp(-(dx*dx+dy*dy)/8))
            }
            return PatchSample(eye: "image_left",hostTime: t,pixels: pixels,roi: LabRect(x: 0,y: 0,width: 128,height: 64),pupil: LabPoint(x: 64,y: 32),rawCandidate: nil,saturation: 0,iris: iris)
        }
        let result = CornealAnalyzer.analyze(eye: "image_left",samples: samples,events: events,start: 0,configuration: c)
        XCTAssertEqual(result.shiftNullCorrelations?.count,63)
        XCTAssertNotNil(result.subpixelPoint); XCTAssertNotNil(result.heldOutSubpixelPoint)
        XCTAssertEqual((result.subpixelPoint?.x ?? 0)*128,64.3,accuracy: 0.25)
    }
}
