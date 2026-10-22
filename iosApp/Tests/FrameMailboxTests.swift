import XCTest
@testable import EyeProject

/// 相机帧信箱：主线程忙时成批送达、积压有上限（延迟不会越积越大）、关闭后不再送达。
final class FrameMailboxTests: XCTestCase {
    func testBacklogIsDeliveredOnceInOrderAndCapped() {
        let delivered = expectation(description: "送达")
        var batches: [[Int]] = []
        let mailbox = FrameMailbox<Int>(limit: 8) { batch in batches.append(batch); delivered.fulfill() }
        // 主线程被占用期间连续到达 20 帧：只派发一次，只保留最新 8 帧
        for i in 0..<20 { mailbox.post(i) }
        wait(for: [delivered], timeout: 2)
        XCTAssertEqual(batches, [Array(12..<20)])
        XCTAssertEqual(mailbox.dropped, 12)
    }

    func testClearDropsFramesNotYetDelivered() {
        var received: [Int] = []
        let mailbox = FrameMailbox<Int>(limit: 8) { received += $0 }
        mailbox.post(1); mailbox.post(2)
        mailbox.clear()
        let settled = expectation(description: "主线程空转一轮")
        DispatchQueue.main.async { settled.fulfill() }
        wait(for: [settled], timeout: 2)
        XCTAssertEqual(received, [])
        // 清空后继续可用：新帧照常送达
        mailbox.post(3)
        let again = expectation(description: "再次送达")
        DispatchQueue.main.async { again.fulfill() }
        wait(for: [again], timeout: 2)
        XCTAssertEqual(received, [3])
    }
}
