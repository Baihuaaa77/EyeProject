import XCTest
@testable import CornealAlgorithms

final class GeometryTests: XCTestCase {
    let iris = IrisRegion(center: LabPoint(x: 0.5,y: 0.5),radiusX: 28.0/128,radiusY: 28.0/64,confidence: 1,
        eyelid: [LabPoint(x: 0.1,y: 0.02),LabPoint(x: 0.9,y: 0.02),LabPoint(x: 0.9,y: 0.98),LabPoint(x: 0.1,y: 0.98)])
    func patch(_ pixels: [Float],time: Double = 0) -> PatchSample {
        PatchSample(eye: "image_left",hostTime: time,pixels: pixels,roi: LabRect(x: 0,y: 0,width: 128,height: 64),pupil: LabPoint(x: 64,y: 32),rawCandidate: nil,saturation: 0,iris: iris,nuisance: [0,0,0,0])
    }
    func spot(_ xRadius: Double = 2,_ yRadius: Double = 2,level: Double = 130) -> [Float] {
        var pixels = [Float](repeating: 40,count: 8192)
        for i in pixels.indices {
            let x = (Double(i%128)+0.5-64)/xRadius
            let y = (Double(i/128)+0.5-32)/yRadius
            pixels[i] += Float(level*exp(-(x*x+y*y)/2))
        }
        return pixels
    }
    func testCircleAcceptedLineAndSinglePixelRejected() {
        XCTAssertTrue(SpotGeometry.shapes(pixels: spot(),iris: iris,roi: patch(spot()).roi).contains { $0.accepted })
        XCTAssertFalse(SpotGeometry.shapes(pixels: spot(7,0.7),iris: iris,roi: patch(spot()).roi).contains { $0.accepted })
        var p = [Float](repeating: 40,count: 8192); p[32*128+64] = 240
        XCTAssertFalse(SpotGeometry.shapes(pixels: p,iris: iris,roi: patch(p).roi).contains { $0.accepted })
    }
    func testNativeResolutionAndSaturationRejectUpsampledOrClippedSpot() {
        let p = spot()
        XCTAssertFalse(SpotGeometry.shapes(pixels: p,iris: iris,roi: LabRect(x: 0,y: 0,width: 10,height: 5)).contains { $0.accepted })
        let clipped = spot(2,2,level: 900).map { min(255,$0) }
        XCTAssertFalse(SpotGeometry.shapes(pixels: clipped,iris: iris,roi: patch(p).roi).contains { $0.accepted })
    }
    func screen(dot: Bool = true,position: Double = 0.25,angle: Double = 0,ratio: Double = 2,diameter: Double = 4) -> [Float] {
        (0..<8192).map { i in
            let dx = Double(i%128)+0.5-64,dy = Double(i/128)+0.5-32
            let x = dx*cos(angle)+dy*sin(angle),y = -dx*sin(angle)+dy*cos(angle)
            if abs(x)>=8 || abs(y)>=8*ratio { return 30 }
            if dot && hypot(x,y-(position-0.5)*16*ratio)<diameter/2 { return 30 }
            return 180
        }
    }
    func testScreenRectangleAndDarkCircleIncludingRotation() throws {
        var c = TrialConfiguration(); c.mode = .screenTarget; c.dotDiameter = 96; c.displayWidth = 390; c.displayHeight = 780
        for angle in [0.0,0.25,-0.3] {
            let o = try XCTUnwrap(ScreenReflection.detect(patch(screen(angle: angle)),configuration: c))
            XCTAssertNotNil(o.darkPoint,o.reason)
            XCTAssertEqual(o.aspectRatio,2,accuracy: 0.3)
        }
    }
    func testScreenRejectsMissingWrongPositionTinyAndWrongAspectTargets() {
        var c = TrialConfiguration(); c.mode = .screenTarget; c.dotDiameter = 96; c.displayWidth = 390; c.displayHeight = 780
        XCTAssertNil(ScreenReflection.detect(patch(screen(dot: false)),configuration: c)?.darkPoint)
        XCTAssertNil(ScreenReflection.detect(patch(screen(position: 0.5)),configuration: c)?.darkPoint)
        XCTAssertNil(ScreenReflection.detect(patch(screen(diameter: 1)),configuration: c)?.darkPoint)
        XCTAssertNil(ScreenReflection.detect(patch(screen(ratio: 1)),configuration: c))
    }
    func testScreenControlCannotProduceCodedOrStructuralEvidence() {
        var c = TrialConfiguration(); c.mode = .screenBlank; c.dotDiameter = 96; c.displayHeight = 780
        let s = (0..<80).map { patch(screen(),time: Double($0)*0.4) }
        let r = ScreenReflection.analyze(eye: "image_left",samples: s,start: 0,configuration: c)
        XCTAssertFalse(r.codedEvidence); XCTAssertFalse(r.screenResult!.structuralEvidence)
        XCTAssertGreaterThan(r.screenResult!.targetCoverage,0.8)
    }
    func testScreenPositiveHasSeparateStructuralEvidence() {
        var c = TrialConfiguration(); c.mode = .screenTarget; c.dotDiameter = 96; c.displayHeight = 780
        let s = (0..<80).map { patch(screen(),time: Double($0)*0.4) }
        let r = CornealAnalyzer.analyze(eye: "image_left",samples: s,events: [],start: 0,configuration: c)
        XCTAssertFalse(r.codedEvidence); XCTAssertTrue(r.screenResult!.structuralEvidence,r.message)
    }
    func coded(trainingOnly: Bool = false,elongated: Bool = false) -> EyeAnalysis {
        var c = TrialConfiguration(); c.mode = .coded
        let events = (0..<64).map { i in DisplayEvent(hostTime: Double(i)*0.5,intensity: 0.5,code: StimulusCode.bits[i],chip: i,callbackHostTime: Double(i)*0.5) }
        let s = (0..<160).map { i -> PatchSample in
            let t = Double(i)/5
            let bit = trainingOnly && t>=16 ? sin(Double(i)*1.71) : StimulusCode.value(at: t-0.1,configuration: c)
            return patch(spot(elongated ? 7 : 2,elongated ? 0.7 : 2,level: 110+30*bit),time: t)
        }
        return CornealAnalyzer.analyze(eye: "image_left",samples: s,events: events,start: 0,configuration: c)
    }
    func testCodedCircleAndHeldOutFailure() {
        let good = coded(); XCTAssertTrue(good.codedEvidence,good.message)
        XCTAssertGreaterThan(good.shapeCoverage ?? 0,0.8)
        XCTAssertFalse(coded(trainingOnly: true).codedEvidence)
        XCTAssertFalse(coded(elongated: true).codedEvidence)
    }
    func testGlobalFlickerAndWrongCodeDoNotPass() {
        var c = TrialConfiguration(); c.mode = .coded
        let events = (0..<64).map { i in DisplayEvent(hostTime: Double(i)*0.5,intensity: 0.5,code: StimulusCode.bits[i],chip: i,callbackHostTime: Double(i)*0.5) }
        for wrongCode in [false,true] {
            let samples = (0..<160).map { i -> PatchSample in
                let t = Double(i)/5,code = StimulusCode.value(at: t,configuration: c,shift: wrongCode ? 13 : 0)
                let pixels = wrongCode ? spot(level: 110+30*code) : spot().map { $0+Float(code*20) }
                return patch(pixels,time: t)
            }
            XCTAssertFalse(CornealAnalyzer.analyze(eye: "image_left",samples: samples,events: events,start: 0,configuration: c).codedEvidence)
        }
    }
    func testScreenAmbiguousHolesAreRejected() throws {
        var c = TrialConfiguration(); c.mode = .screenTarget; c.dotDiameter = 96; c.displayHeight = 780
        var p = screen()
        for i in p.indices where hypot(Double(i%128)+0.5-64,Double(i/128)+0.5-29)<2 { p[i] = 30 }
        let r = try XCTUnwrap(ScreenReflection.detect(patch(p),configuration: c))
        XCTAssertNil(r.darkPoint,r.reason)
    }
    func testMalformedDataAndConfigurationDoNotCrash() throws {
        XCTAssertNil(CornealAnalyzer.correlation([Double](repeating: .nan,count: 20),[Double](repeating: 1,count: 20)))
        XCTAssertNil(MotionDecoder.fit([[Double]](repeating: [1,2],count: 21)+[[]],[LabPoint](repeating: LabPoint(x: 0,y: 0),count: 22)))
        var c = TrialConfiguration(); c.chipSeconds = 0
        XCTAssertNotNil(c.validationError); XCTAssertEqual(StimulusCode.value(at: 3,configuration: c),0)
        let p = patch(spot()); let s = CornealAnalyzer.validSamples([p,p,patch(spot(),time: -1)],eye: "image_left",start: 0,duration: 32)
        XCTAssertEqual(s.count,1)
        let old = try JSONDecoder().decode(TrialConfiguration.self,from: Data("{\"mode\":\"motion\",\"dotDiameter\":96}".utf8))
        XCTAssertEqual(old.dotDiameter,24); XCTAssertEqual(old.probeDiameter,96)
    }
    func testTrainingWinnerSurvivesMissingHeldOutInsteadOfBeingSilentlyDropped() throws {
        var c = TrialConfiguration(); c.mode = .motion
        let events = (0..<960).map { i in DisplayEvent(hostTime: Double(i)/30,intensity: 1,code: 0,chip: i/15,callbackHostTime: Double(i)/30,probes: MotionStimulus.probes(at: Double(i)/30,configuration: c)) }
        let obs = (0..<80).map { i -> MotionObservation in
            let t = Double(i)/5,p = MotionStimulus.probes(at: t,configuration: c)[0].position
            return MotionObservation(time: t,point: LabPoint(x: 60+p.x*12,y: 30+p.y*10),nuisance: [0,0,0,0])
        }
        let r = try XCTUnwrap(MotionDecoder.decode(track: MotionDecoder.Track(observations: obs),id: 0,color: "white",events: events,start: 0,duration: 32,lag: 0,expectedSamples: 160))
        XCTAssertGreaterThan(r.trainGain,0.9); XCTAssertFalse(r.accepted)
    }
}
