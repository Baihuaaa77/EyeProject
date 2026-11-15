import XCTest
@testable import CornealAlgorithms

final class MotionRegressionTests: XCTestCase {
    private var iris: IrisRegion { IrisRegion(center: LabPoint(x: 0.5,y: 0.5),radiusX: 20.0/128,radiusY: 20.0/64,confidence: 1,eyelid: [LabPoint(x: 0.2,y: 0.2),LabPoint(x: 0.8,y: 0.2),LabPoint(x: 0.8,y: 0.8),LabPoint(x: 0.2,y: 0.8)]) }
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

}
