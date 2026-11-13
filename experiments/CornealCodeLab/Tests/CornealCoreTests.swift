import XCTest
import SwiftUI
@testable import CornealCodeLab

final class CornealCoreTests: XCTestCase {
    func testCodeIsBalancedAndNeverExceedsConfiguredIntensity() {
        XCTAssertEqual(StimulusCode.bits.count,64)
        XCTAssertEqual(StimulusCode.bits.reduce(0,+),0)
        XCTAssertTrue(StimulusCode.bits.allSatisfy { abs($0) == 1 })
        var c = TrialConfiguration(); c.mode = .coded
        for bit in 0..<64 {
            let v = StimulusCode.intensity(at: Double(bit)*c.chipSeconds,configuration: c)
            XCTAssertTrue(v >= 0 && v <= 1)
        }
    }

    func testConstantSignalsHaveNoDefinedCorrelation() {
        XCTAssertNil(CornealAnalyzer.correlation([Double](repeating: 20,count: 60),StimulusCode.bits.prefix(60).map { $0 }))
    }

    func testCodedSpotWinsAgainstABrighterStaticReflectionAndValidatesHeldOut() {
        var c = TrialConfiguration(); c.mode = .coded
        let start = 100.0, spot = 16*64+32, staticLamp = 16*64+45
        let events = (0..<64).map { i in DisplayEvent(hostTime: start+Double(i)*0.5,intensity: 0.8+StimulusCode.bits[i]*0.15,
            code: StimulusCode.bits[i],chip: i,callbackHostTime: start+Double(i)*0.5) }
        var samples: [PatchSample] = []
        for frame in 0..<480 {
            let t = Double(frame)/15
            var pixels = [Float](repeating: 90,count: CornealAnalyzer.count)
            pixels[staticLamp] = 245
            pixels[spot] = Float(150+8*StimulusCode.value(at: t-0.1,configuration: c)+0.2*sin(Double(frame)))
            samples.append(PatchSample(eye: "image_left",hostTime: start+t,pixels: pixels,
                roi: LabRect(x: 0,y: 0,width: 100,height: 50),pupil: LabPoint(x: 50,y: 25),rawCandidate: nil,saturation: 0))
        }
        let result = CornealAnalyzer.analyze(eye: "image_left",samples: samples,events: events,start: start,configuration: c)
        XCTAssertTrue(result.codedEvidence,result.message)
        XCTAssertGreaterThan(result.heldOutCorrelation ?? 0,0.8)
        XCTAssertEqual(result.point?.x ?? 0,(32.5/64),accuracy: 0.02)
    }

    func testTrainingOnlyArtifactDoesNotPassHeldOutValidation() {
        var c = TrialConfiguration(); c.mode = .coded
        let events = (0..<64).map { i in DisplayEvent(hostTime: Double(i)*0.5,intensity: 0.8,code: StimulusCode.bits[i],chip: i,callbackHostTime: Double(i)*0.5) }
        let samples = (0..<320).map { i -> PatchSample in
            let t = Double(i)/10
            var p = [Float](repeating: 100,count: CornealAnalyzer.count)
            p[16*64+32] = Float(150+(t<16 ? 8*StimulusCode.value(at: t,configuration: c) : sin(Double(i)*0.6)))
            return PatchSample(eye: "image_left",hostTime: t,pixels: p,roi: LabRect(x: 0,y: 0,width: 100,height: 50),
                pupil: LabPoint(x: 50,y: 25),rawCandidate: nil,saturation: 0)
        }
        XCTAssertFalse(CornealAnalyzer.analyze(eye: "image_left",samples: samples,events: events,start: 0,configuration: c).codedEvidence)
    }

    @MainActor func testSetupScreenRendersWithoutStartingTheCamera() throws {
        let model = LabModel()
        let renderer = ImageRenderer(content: LabRootView(model: model).frame(width: 390,height: 844).preferredColorScheme(.dark))
        renderer.scale = 1
        let image = try XCTUnwrap(renderer.uiImage)
        let attachment = XCTAttachment(image: image); attachment.name = "光码验证-准备页"; attachment.lifetime = .keepAlways
        add(attachment)
        XCTAssertGreaterThan(try XCTUnwrap(image.pngData()).count,2_000)
    }
}
