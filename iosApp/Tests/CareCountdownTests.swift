import XCTest
@testable import EyeProject

final class CareCountdownTests: XCTestCase {
    func testStartsOnlyOnRequestAndCountsElapsedTime() {
        let now = Date(timeIntervalSince1970: 1000)
        var timer = CareCountdown()
        XCTAssertEqual(timer.remaining(total: 30,at: now.addingTimeInterval(10)),30)
        XCTAssertFalse(timer.complete(total: 30,at: now.addingTimeInterval(31)))
        timer.start(at: now)
        XCTAssertEqual(ceil(timer.remaining(total: 30,at: now.addingTimeInterval(1.2))),29)
        timer.start(at: now.addingTimeInterval(10))
        XCTAssertEqual(ceil(timer.remaining(total: 30,at: now.addingTimeInterval(29.1))),1)
        XCTAssertFalse(timer.complete(total: 30,at: now.addingTimeInterval(29.9)))
        XCTAssertTrue(timer.complete(total: 30,at: now.addingTimeInterval(30)))
        XCTAssertFalse(timer.complete(total: 30,at: now.addingTimeInterval(31)))
    }

    func testEarlyExitOrBackgroundCannotCompleteLater() {
        let now = Date(timeIntervalSince1970: 1000)
        for seconds in [20.0,30.0,180.0] {
            var timer = CareCountdown()
            timer.start(at: now)
            timer.cancel()
            XCTAssertFalse(timer.complete(total: seconds,at: now.addingTimeInterval(seconds + 1)))
            XCTAssertFalse(timer.completed)
        }
    }
}
