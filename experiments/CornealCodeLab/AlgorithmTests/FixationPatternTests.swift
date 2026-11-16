import XCTest
@testable import CornealAlgorithms

final class FixationPatternTests: XCTestCase {
    private func image(pattern: FixationPattern?,diameter: Double,angle: Double = 0) -> [Float] {
        let size = 80,cx = 40.3,cy = 39.7
        var image = [Float](repeating: 0,count: size*size)
        for y in 0..<size { for x in 0..<size {
            var value = 0.0
            for sy in 0..<4 { for sx in 0..<4 {
                let dx = (Double(x)+(Double(sx)+0.5)/4-cx)/diameter
                let dy = (Double(y)+(Double(sy)+0.5)/4-cy)/diameter
                let u = dx*cos(angle)+dy*sin(angle),v = -dx*sin(angle)+dy*cos(angle)
                value += pattern?.intensity(x: u,y: v) ?? (hypot(u,v)<=0.1 ? 1 : 0)
            } }
            image[y*size+x] = Float(30+180*value/16+sin(Double(x*11+y*7))*1.5)
        } }
        // 独立像素采样及空间模糊，包含亚像素偏移、噪声和与模板不同的PSF。
        var blurred = image
        for y in 1..<(size-1) { for x in 1..<(size-1) {
            var sum: Float = 0
            for dy in -1...1 { for dx in -1...1 { sum += image[(y+dy)*size+x+dx]*Float((dx == 0 ? 2 : 1)*(dy == 0 ? 2 : 1))/16 } }
            blurred[y*size+x] = sum
        } }
        return blurred
    }
    func testBlurredRotatedPatternsBeatBothCompetingExplanations() throws {
        for pattern in FixationPattern.allCases {
            let match = try XCTUnwrap(FixationPatternMatcher.match(pixels: image(pattern: pattern,diameter: 18,angle: .pi/4),width: 80,height: 80,center: LabPoint(x: 40.3,y: 39.7),expectedDiameter: 18,pattern: pattern))
            XCTAssertGreaterThan(match.correlation,0.7)
            XCTAssertTrue(match.resolved,"\(pattern): \(match)")
            XCTAssertEqual(match.diameterPixels,18,accuracy: 3)
        }
    }
    func testPlainSpotCannotVerifyAnOuterPattern() throws {
        for pattern in FixationPattern.allCases {
            let match = try XCTUnwrap(FixationPatternMatcher.match(pixels: image(pattern: nil,diameter: 18),width: 80,height: 80,center: LabPoint(x: 40.3,y: 39.7),expectedDiameter: 18,pattern: pattern))
            XCTAssertFalse(match.resolved)
            XCTAssertGreaterThan(match.alternativeCorrelation,match.correlation)
        }
    }
    func testSubresolutionAndFlatPatchCannotClaimResolvedStructure() {
        let tiny = FixationPatternMatcher.match(pixels: image(pattern: .ringDot,diameter: 5),width: 80,height: 80,center: LabPoint(x: 40.3,y: 39.7),expectedDiameter: 5,pattern: .ringDot)
        XCTAssertFalse(tiny?.resolved == true)
        XCTAssertNil(FixationPatternMatcher.match(pixels: [Float](repeating: 40,count: 6400),width: 80,height: 80,center: LabPoint(x: 40,y: 40),expectedDiameter: 16,pattern: .ringDot))
    }
    func testNewTargetConfigFitsScreenAndHistoricalModesRemainReadableOnly() throws {
        let c = TrialConfiguration.patternDefault
        XCTAssertNil(c.validationError)
        XCTAssertEqual(c.centerDiameter,57.6,accuracy: 0.001)
        let restored = try JSONDecoder().decode(TrialConfiguration.self,from: JSONEncoder().encode(c))
        XCTAssertEqual(restored.targetPattern,.ringDot)
        XCTAssertTrue(restored.mode.isPattern)
        for mode in [TrialMode.motion,.screenTarget,.screenBlank] {
            XCTAssertFalse(TrialMode.available.contains(mode))
            XCTAssertEqual(try JSONDecoder().decode(TrialMode.self,from: JSONEncoder().encode(mode)),mode)
        }
        var clipped = c; clipped.dotY = 0.1
        XCTAssertNotNil(clipped.validationError)
    }
    func testSwitchingToMeanControlPreservesCodedMeanIncludingLegacyWeakCode() {
        var c = TrialConfiguration.patternDefault
        for modulation in [0.15,0.4,0.475,0.5] {
            c.modulation = modulation; c.selectMode(.coded)
            let mean = c.mean
            c.selectMode(.steady)
            XCTAssertEqual(c.mean,mean)
            XCTAssertEqual(c.mean,modulation == 0.15 ? 0.8 : 1-modulation)
        }
    }
}
