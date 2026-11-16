import XCTest
@testable import CornealAlgorithms

final class StimulusTimingTests: XCTestCase {
    func testSixtyPercentAreaIsNotSixtyPercentRadius() {
        XCTAssertEqual(IrisSearch.radiusFraction*IrisSearch.radiusFraction,0.6,accuracy: 1e-12)
        XCTAssertGreaterThan(IrisSearch.radiusFraction,0.77)
        XCTAssertLessThan(IrisSearch.radiusFraction,0.78)
    }
    func testThreeCodesEmitTrueBlackAndCorrectMean() {
        for scheme in CodingScheme.allCases {
            var c = TrialConfiguration.pointDefault; c.codingScheme = scheme
            let values = (0..<6400).map { StimulusCode.intensity(at: (Double($0)+0.5)*c.duration/6400,configuration: c) }
            XCTAssertEqual(values.min(),0); XCTAssertEqual(values.max(),1)
            XCTAssertEqual(values.reduce(0,+)/Double(values.count),scheme.meanReference,accuracy: 0.001)
            c.selectMode(.steady)
            XCTAssertEqual(c.mean,scheme.meanReference,accuracy: 1e-12)
        }
    }
    func testManchesterHasOppositeHalvesAndSparsePulseLeavesLongDarkWindow() {
        var c = TrialConfiguration.pointDefault; c.codingScheme = .manchester
        XCTAssertEqual(StimulusCode.value(at: 0.1,configuration: c),-StimulusCode.value(at: 0.4,configuration: c))
        c.codingScheme = .sparsePulse
        XCTAssertEqual(StimulusCode.intensity(at: 0.1,configuration: c),1)
        XCTAssertEqual(StimulusCode.intensity(at: 0.4,configuration: c),0)
    }
    func testNearCameraIsHighestCompleteCircleBelowSafeArea() {
        let c = TrialConfiguration.pointDefault
        let p = StimulusGeometry.center(configuration: c,width: 393,height: 852,safeTop: 59,safeBottom: 34)
        XCTAssertEqual(p.y,59+c.dotDiameter/2+2,accuracy: 1e-12)
        XCTAssertLessThan(p.y,852*0.15)
        XCTAssertGreaterThanOrEqual(p.y-c.dotDiameter/2,59)
    }
    func testExposureTransitionAndFivePercentAreNotCountedAsTrueBlack() {
        let events = [DisplayEvent(hostTime: 0,intensity: 1,code: 1,chip: 0,callbackHostTime: 0),
                      DisplayEvent(hostTime: 0.5,intensity: 0,code: -1,chip: 1,callbackHostTime: 0.5),
                      DisplayEvent(hostTime: 1,intensity: 0.05,code: -1,chip: 2,callbackHostTime: 1)]
        let timeline = EmissionTimeline(events: events)
        func phase(_ time: Double) -> EmissionPhase { timeline.window(time: time,exposure: 0.02,fps: 30,lag: 0.033,chipSeconds: 0.5).phase }
        XCTAssertEqual(phase(0.52),.transition)
        XCTAssertEqual(phase(0.8),.off)
        XCTAssertEqual(phase(1.25),.dim)
        XCTAssertEqual(phase(-1),.unknown)
    }
    func testHistoricalRecordsKeepOriginalLevelsAndPlacement() throws {
        let c = try JSONDecoder().decode(TrialConfiguration.self,from: Data("{\"mode\":\"coded\",\"mean\":0.525,\"modulation\":0.475,\"dotY\":0.15}".utf8))
        XCTAssertEqual(c.codingScheme,.mSequence); XCTAssertEqual(c.dotPlacement,.legacyFraction)
        XCTAssertEqual(c.mean-c.modulation,0.05,accuracy: 1e-12)
        XCTAssertEqual(TrialMode.available,[.coded,.steady,.dark])
        var legacy = c; legacy.modulation = 0.15; legacy.selectMode(.coded)
        let mean = legacy.mean; legacy.selectMode(.steady)
        XCTAssertEqual(legacy.mean,mean)
    }
}
