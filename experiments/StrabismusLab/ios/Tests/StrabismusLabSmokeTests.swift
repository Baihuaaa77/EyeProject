import EyeScreening
import XCTest

final class StrabismusLabSmokeTests: XCTestCase {
    func testResearchRadiusDefaultIs080() {
        XCTAssertEqual(ScreenPointOptics.shared.RESEARCH_SEARCH_RADIUS, 0.80, accuracy: 0.0001)
        let cfg = LabProtocolConfig(
            subjectId: "IOS-TEST",
            glassesState: "裸眼",
            lightingCondition: "室内",
            clinicalNote: "",
            codingScheme: .mSequence,
            searchRadiusFraction: 0.80,
            chipDurationMs: 250,
            stimulusDiameterMm: 34.0,
            nearDurationSec: 16,
            farDurationSec: 12,
            pursuitDurationSec: 18,
            includeNear: true,
            includeFar: true,
            includeCover: true,
            includePursuit: true,
            lightOffsetXMm: 0.0,
            lightOffsetYMm: 22.0
        )
        XCTAssertEqual(cfg.searchRadiusFraction, 0.80, accuracy: 0.0001)
        XCTAssertEqual(cfg.activeStages.count, 4)
    }
}
