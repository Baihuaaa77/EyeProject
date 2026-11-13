import XCTest
import SwiftUI
@testable import CornealCodeLab

final class CornealCoreTests: XCTestCase {
    private var iris: IrisRegion { IrisRegion(center: LabPoint(x: 0.5,y: 0.5),radiusX: 20.0/128,radiusY: 20.0/64,confidence: 50,
        eyelid: [LabPoint(x: 0.2,y: 0.2),LabPoint(x: 0.8,y: 0.2),LabPoint(x: 0.8,y: 0.8),LabPoint(x: 0.2,y: 0.8)]) }
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
        let start = 100.0, spot = 32*128+64, staticLamp = 32*128+90
        let events = (0..<64).map { i in DisplayEvent(hostTime: start+Double(i)*0.5,intensity: 0.8+StimulusCode.bits[i]*0.15,
            code: StimulusCode.bits[i],chip: i,callbackHostTime: start+Double(i)*0.5) }
        var samples: [PatchSample] = []
        for frame in 0..<480 {
            let t = Double(frame)/15
            var pixels = [Float](repeating: 90,count: CornealAnalyzer.count)
            pixels[staticLamp] = 245
            pixels[spot] = Float(150+8*StimulusCode.value(at: t-0.1,configuration: c)+0.2*sin(Double(frame)))
            samples.append(PatchSample(eye: "image_left",hostTime: start+t,pixels: pixels,
                roi: LabRect(x: 0,y: 0,width: 100,height: 50),pupil: LabPoint(x: 50,y: 25),rawCandidate: nil,saturation: 0,iris: iris))
        }
        let result = CornealAnalyzer.analyze(eye: "image_left",samples: samples,events: events,start: start,configuration: c)
        XCTAssertTrue(result.codedEvidence,result.message)
        XCTAssertGreaterThan(result.heldOutCorrelation ?? 0,0.8)
        XCTAssertEqual(result.point?.x ?? 0,(64.5/128),accuracy: 0.02)
    }

    func testTrainingOnlyArtifactDoesNotPassHeldOutValidation() {
        var c = TrialConfiguration(); c.mode = .coded
        let events = (0..<64).map { i in DisplayEvent(hostTime: Double(i)*0.5,intensity: 0.8,code: StimulusCode.bits[i],chip: i,callbackHostTime: Double(i)*0.5) }
        let samples = (0..<320).map { i -> PatchSample in
            let t = Double(i)/10
            var p = [Float](repeating: 100,count: CornealAnalyzer.count)
            p[32*128+64] = Float(150+(t<16 ? 8*StimulusCode.value(at: t,configuration: c) : sin(Double(i)*0.6)))
            return PatchSample(eye: "image_left",hostTime: t,pixels: p,roi: LabRect(x: 0,y: 0,width: 100,height: 50),
                pupil: LabPoint(x: 50,y: 25),rawCandidate: nil,saturation: 0,iris: iris)
        }
        XCTAssertFalse(CornealAnalyzer.analyze(eye: "image_left",samples: samples,events: events,start: 0,configuration: c).codedEvidence)
    }

    func testIrisBoundaryRequiresScleralEdgesAndRejectsCornerPeaks() throws {
        let w = CornealAnalyzer.width,h = CornealAnalyzer.height
        var p = [Float](repeating: 150,count: w*h)
        for y in 0..<h { for x in 0..<w where hypot(Double(x-w/2),Double(y-h/2))<20 { p[y*w+x] = 50 } }
        let region = try XCTUnwrap(IrisRegion.estimate(pixels: p,eyelid: iris.eyelid))
        XCTAssertTrue(region.contains(LabPoint(x: 0.5,y: 0.5)))
        XCTAssertFalse(region.contains(LabPoint(x: 0.75,y: 0.5)))
        XCTAssertNil(IrisRegion.estimate(pixels: [Float](repeating: 80,count: w*h),eyelid: iris.eyelid))
        p[32*w+94] = 255
        let sample = PatchSample(eye: "image_left",hostTime: 0,pixels: p,roi: LabRect(x: 0,y: 0,width: 128,height: 64),pupil: LabPoint(x: 64,y: 32),rawCandidate: nil,saturation: 0,iris: region)
        let tracks = MotionDecoder.tracks(samples: (0..<70).map { i in let s = sample; return PatchSample(eye: s.eye,hostTime: Double(i)/10,pixels: s.pixels,roi: s.roi,pupil: s.pupil,rawCandidate: nil,saturation: 0,iris: region) },color: "white",start: 0)
        XCTAssertTrue(tracks.allSatisfy { track in track.observations.allSatisfy { region.contains(LabPoint(x: $0.point.x/Double(w),y: $0.point.y/Double(h))) } })
        XCTAssertFalse(tracks.contains { $0.observations.contains { $0.point.x>85 } })
    }

    func testCodedEyeCornerCannotWinEvenWithPerfectCode() {
        var c = TrialConfiguration(); c.mode = .coded
        let events = (0..<64).map { i in DisplayEvent(hostTime: Double(i)/2,intensity: 1,code: StimulusCode.bits[i],chip: i,callbackHostTime: Double(i)/2) }
        let samples = (0..<160).map { i -> PatchSample in
            let t = Double(i)/5
            var p = [Float](repeating: 100,count: CornealAnalyzer.count)
            p[32*128+94] = Float(180+40*StimulusCode.value(at: t,configuration: c))
            return PatchSample(eye: "image_left",hostTime: t,pixels: p,roi: LabRect(x: 0,y: 0,width: 128,height: 64),pupil: LabPoint(x: 64,y: 32),rawCandidate: nil,saturation: 0,iris: iris)
        }
        let result = CornealAnalyzer.analyze(eye: "image_left",samples: samples,events: events,start: 0,configuration: c)
        XCTAssertFalse(result.codedEvidence)
        if let point = result.point { XCTAssertTrue(iris.contains(point)) }
        let missing = samples.map { s in PatchSample(eye: s.eye,hostTime: s.hostTime,pixels: s.pixels,roi: s.roi,pupil: s.pupil,rawCandidate: nil,saturation: 0) }
        XCTAssertNil(CornealAnalyzer.analyze(eye: "image_left",samples: missing,events: events,start: 0,configuration: c).point)
    }

    func testOldConfigurationStillDecodesWithoutChangingItsIntensity() throws {
        let c = try JSONDecoder().decode(TrialConfiguration.self,from: Data("{\"mode\":\"coded\",\"mean\":0.8,\"modulation\":0.15}".utf8))
        XCTAssertEqual(c.mean,0.8); XCTAssertEqual(c.modulation,0.15)
        XCTAssertEqual(c.probeCount,1); XCTAssertFalse(c.colorfulProbes)
    }

    func testStrongIntensityAndIndependentPeripheralTrajectories() {
        var c = TrialConfiguration(); c.mode = .coded
        let values = (0..<64).map { StimulusCode.intensity(at: Double($0)*0.5,configuration: c) }
        XCTAssertEqual(values.min() ?? -1,0.05,accuracy: 0.0001)
        XCTAssertEqual(values.max(),1)
        c.mode = .motion; c.probeCount = 3; c.colorfulProbes = true
        var traces = Array(repeating: [Double](),count: 3)
        for i in 0..<960 {
            let probes = MotionStimulus.probes(at: Double(i)/30,configuration: c,width: 320,height: 568)
            XCTAssertEqual(probes.map(\.color),["red","green","blue"])
            for p in probes {
                XCTAssertGreaterThanOrEqual(p.position.x*320,c.dotDiameter/2)
                XCTAssertLessThanOrEqual(p.position.y*568,568-c.dotDiameter/2)
                XCTAssertGreaterThan(hypot((p.position.x-0.5)*320,(p.position.y-c.dotY)*568),c.dotDiameter/2+12)
                traces[p.id].append(p.position.x)
            }
        }
        XCTAssertLessThan(abs(CornealAnalyzer.correlation(traces[0],traces[1]) ?? 1),0.6)
    }

    private func motionFixture(following: Bool = false, trainingOnly: Bool = false) -> (TrialConfiguration,[DisplayEvent],MotionDecoder.Track) {
        var c = TrialConfiguration(); c.mode = .motion
        let events = (0..<960).map { i -> DisplayEvent in
            let t = Double(i)/30
            return DisplayEvent(hostTime: t,intensity: 1,code: 0,chip: Int(t/0.5),callbackHostTime: t,probes: MotionStimulus.probes(at: t,configuration: c))
        }
        let obs = (2..<480).map { i -> MotionObservation in
            let t = Double(i)/15, p = MotionDecoder.reference(t-0.1,id: 0,events: events)!
            let q = trainingOnly && t>=16 ? MotionStimulus.probes(at: t+5,configuration: c)[0].position : p
            let nuisance = following ? [p.x,p.y,0,0] : [sin(t*1.43)*0.01,cos(t*0.93)*0.01,0,0]
            return MotionObservation(time: t,point: LabPoint(x: 64+12*q.x+2*q.y+3*nuisance[0]+0.01*sin(t*23),y: 30-3*q.x+9*q.y+0.01*cos(t*17)),nuisance: nuisance)
        }
        return (c,events,MotionDecoder.Track(observations: obs))
    }

    func testMotionFitsTrainingAndValidatesUnseenHalfAgainstWrongTrajectory() throws {
        let (c,events,track) = motionFixture()
        let result = try XCTUnwrap(MotionDecoder.decode(track: track,id: 0,color: "white",events: events,start: 0,duration: c.duration,lag: 0.1,expectedSamples: 480))
        XCTAssertTrue(result.accepted,result.message)
        XCTAssertGreaterThan(result.heldOutGain,0.95)
        XCTAssertGreaterThan(result.heldOutGain-result.wrongTrajectoryGain,0.20)
    }

    func testFollowingTheProbeIsConfoundedAndCannotPass() throws {
        let (c,events,track) = motionFixture(following: true)
        let result = try XCTUnwrap(MotionDecoder.decode(track: track,id: 0,color: "white",events: events,start: 0,duration: c.duration,lag: 0.1,expectedSamples: 480))
        XCTAssertTrue(result.confounded); XCTAssertFalse(result.accepted)
    }

    func testTrainingOnlyMotionDoesNotValidateAndStaticReflectionDoesNotPass() throws {
        let (c,events,track) = motionFixture(trainingOnly: true)
        let result = try XCTUnwrap(MotionDecoder.decode(track: track,id: 0,color: "white",events: events,start: 0,duration: c.duration,lag: 0.1,expectedSamples: 480))
        XCTAssertFalse(result.accepted)
        var stationary = track
        stationary.observations = track.observations.map { MotionObservation(time: $0.time,point: LabPoint(x: 70,y: 32),nuisance: $0.nuisance) }
        let control = try XCTUnwrap(MotionDecoder.decode(track: stationary,id: 0,color: "white",events: events,start: 0,duration: c.duration,lag: 0.1,expectedSamples: 480))
        XCTAssertFalse(control.accepted); XCTAssertLessThan(control.amplitudePixels,0.18)
    }

    func testColoredPeakTrackingSeparatesThreeSourcesFromBrighterStaticLamp() throws {
        var c = TrialConfiguration(); c.mode = .motion; c.probeCount = 3; c.colorfulProbes = true
        let events = (0..<960).map { i -> DisplayEvent in
            let t = Double(i)/30
            return DisplayEvent(hostTime: t,intensity: 1,code: 0,chip: Int(t/0.5),callbackHostTime: t,probes: MotionStimulus.probes(at: t,configuration: c))
        }
        let samples = (1..<160).map { i -> PatchSample in
            let t = Double(i)/5
            var rgb = [UInt8](repeating: 40,count: CornealAnalyzer.count*3)
            for probe in MotionStimulus.probes(at: t,configuration: c) {
                let px = 54+Double(probe.id)*10+8*(probe.position.x-(probe.id == 0 ? 0.22 : 0.78))
                let py = 32+9*(probe.position.y-(probe.id<2 ? 0.75 : 0.17))
                for y in (Int(py)-3)...(Int(py)+3) { for x in (Int(px)-3)...(Int(px)+3) {
                    let v = 180*exp(-(pow(Double(x)+0.5-px,2)+pow(Double(y)+0.5-py,2))/1.3)
                    rgb[(y*CornealAnalyzer.width+x)*3+probe.id] = UInt8(40+v)
                } }
            }
            for channel in 0..<3 { rgb[(32*128+93)*3+channel] = 250 }
            var gray = [Float](repeating: 0,count: CornealAnalyzer.count)
            for j in gray.indices {
                let red = Float(rgb[j*3])*0.2126
                let green = Float(rgb[j*3+1])*0.7152
                let blue = Float(rgb[j*3+2])*0.0722
                gray[j] = red+green+blue
            }
            return PatchSample(eye: "image_left",hostTime: t,pixels: gray,roi: LabRect(x: 0,y: 0,width: 128,height: 64),pupil: LabPoint(x: 64,y: 32),rawCandidate: nil,saturation: 0,iris: iris,rgb: rgb,nuisance: [0,0,0,0])
        }
        let result = MotionDecoder.analyze(eye: "image_left",samples: samples,events: events,start: 0,configuration: c)
        XCTAssertEqual(result.motionResults?.count,3)
        XCTAssertEqual(result.motionResults?.filter { $0.accepted }.count,3,result.message)
    }

    @MainActor func testMotionStimulusRendersFixedWhitePointAndThreeProbes() throws {
        let driver = StimulusDriver()
        driver.configuration.mode = .motion; driver.configuration.probeCount = 3; driver.configuration.colorfulProbes = true
        let view = UIView(frame: CGRect(x: 0,y: 0,width: 390,height: 844)); driver.attach(view)
        let image = UIGraphicsImageRenderer(size: view.bounds.size).image { ctx in view.layer.render(in: ctx.cgContext) }
        let attachment = XCTAttachment(image: image); attachment.name = "运动刺激-固定注视与三个彩色点"; attachment.lifetime = .keepAlways; add(attachment)
        XCTAssertGreaterThan(try XCTUnwrap(image.pngData()).count,2_000)
        let (_,events,track) = motionFixture()
        let result = try XCTUnwrap(MotionDecoder.decode(track: track,id: 0,color: "white",events: events,start: 0,duration: 32,lag: 0.1,expectedSamples: 480))
        let renderer = ImageRenderer(content: MotionResultView(result: result).padding().frame(width: 390).background(LabStyle.background).preferredColorScheme(.dark))
        let resultImage = try XCTUnwrap(renderer.uiImage)
        let resultAttachment = XCTAttachment(image: resultImage); resultAttachment.name = "运动解调-合成验证结果"; resultAttachment.lifetime = .keepAlways; add(resultAttachment)
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
