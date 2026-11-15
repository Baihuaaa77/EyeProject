import XCTest
import UIKit
@testable import CornealCodeLab

final class ScreenModeTests: XCTestCase {
    @MainActor func testFullScreenUsesBlackTargetAndBlankControl() throws {
        let driver = StimulusDriver()
        var c = TrialConfiguration(); c.mode = .screenTarget; c.screenLevel = 0.7; c.dotDiameter = 96
        driver.warmup(c); defer { driver.stop() }
        let view = UIView(frame: CGRect(x: 0,y: 0,width: 390,height: 844))
        driver.attach(view); driver.begin(at: 100); driver.advance(to: 101)
        let dot = try XCTUnwrap(view.layer.sublayers?.first)
        XCTAssertEqual(view.backgroundColor,UIColor(white: 0.7,alpha: 1))
        XCTAssertEqual(dot.backgroundColor,UIColor(white: 0,alpha: 1).cgColor)
        XCTAssertFalse(dot.isHidden); XCTAssertEqual(dot.bounds.width,96)
        XCTAssertEqual(driver.events.last?.intensity,0.7)
        c.mode = .screenBlank
        driver.warmup(c); driver.attach(view); driver.begin(at: 100); driver.advance(to: 101)
        XCTAssertTrue(dot.isHidden)
        XCTAssertEqual(view.backgroundColor,UIColor(white: 0.7,alpha: 1))
    }
}
