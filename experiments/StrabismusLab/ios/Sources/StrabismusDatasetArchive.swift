import Foundation

/// 快捷数据集采集目标距离（仅 25 cm 与 33 cm 两档）。
enum DatasetTargetDistance: Int, CaseIterable, Identifiable, Codable {
    case cm25 = 25
    case cm33 = 33

    var id: Int { rawValue }
    var cm: Float { Float(rawValue) }
    var label: String { "\(rawValue) cm" }
    var folderTag: String { "\(rawValue)cm" }
}

/// 快捷数据集戴镜标签（仅裸眼与戴镜两种）。
enum DatasetGlassesOption: String, CaseIterable, Identifiable, Codable {
    case bare = "bare"
    case glasses = "glasses"

    var id: String { rawValue }
    var label: String {
        switch self {
        case .bare: return "裸眼"
        case .glasses: return "戴镜"
        }
    }
}

/// 快捷采集单次样本元数据（写入 `dataset_meta.json`，供电脑端离线批量评测使用）。
struct DatasetSampleMeta: Codable {
    struct CameraIntrinsicsMeta: Codable {
        let fx: Double
        let fy: Double
        let cx: Double
        let cy: Double
        let lightOffsetXMm: Double
        let lightOffsetYMm: Double
        let source: String
    }

    struct DistanceStatsMeta: Codable {
        let minCm: Double
        let maxCm: Double
        let meanCm: Double
        let maxAbsErrorCm: Double
    }

    let schemaVersion: String
    let sampleId: String
    let subjectId: String
    let targetDistanceCm: Int
    let distanceToleranceCm: Double
    let glasses: String
    let glassesLabel: String
    let durationSec: Int
    let codingScheme: String
    let chipDurationMs: Int64
    let stimulusDiameterMm: Double
    let searchRadiusFraction: Double
    let deviceModel: String
    let platformName: String
    let clockDomain: String
    let sourceWidth: Int
    let sourceHeight: Int
    let videoWidth: Int
    let videoHeight: Int
    let cameraIntrinsics: CameraIntrinsicsMeta
    let distanceStats: DistanceStatsMeta
    let videoFrameCount: Int
    let displayEventCount: Int
    let restartCount: Int
    let createdAtMs: Int64
    let createdAtIso: String
}

/// 本地已保存的快捷采集样本条目（对应 `Documents/StrabismusDataset/<sampleId>/`）。
struct StoredDatasetSample: Identifiable {
    let id: String
    let directory: URL
    let meta: DatasetSampleMeta
    let hasVideo: Bool
    let hasVideoFrames: Bool
    let hasDisplayEvents: Bool
    let totalBytes: Int64
}

/// 严格距离监督器：
/// - 开始前：仅当检测到人脸且实时距离持续处于 `target ± toleranceCm`（默认 ±2.0 cm）达到 `armHoldMs`（默认 600 ms）才放行自动开录；
/// - 采集中：一旦距离误差超过 `toleranceCm` 持续 `maxOutOfRangeFrames` 帧（或单帧剧烈越界 / 连续丢失人脸），立即触发中断并自动重置重来。
struct DatasetDistanceSupervisor {
    enum ArmUpdate: Equatable {
        case waiting(distanceCm: Float?, inRange: Bool, holdProgress: Double, hint: String)
        case readyToStart(distanceCm: Float)
    }

    enum RecordUpdate: Equatable {
        case ok(distanceCm: Float, deltaCm: Float)
        case abort(reason: String, speech: String)
    }

    let targetCm: Float
    let toleranceCm: Float
    let armHoldMs: Int64
    let maxOutOfRangeFrames: Int
    let severeOutOfRangeCm: Float
    let maxMissingFaceFrames: Int

    private var recentDistances: [Float] = []
    private var armStableStartMs: Int64?
    private var outOfRangeStreak = 0
    private var missingFaceStreak = 0

    init(
        targetCm: Float,
        toleranceCm: Float = 2.0,
        armHoldMs: Int64 = 600,
        maxOutOfRangeFrames: Int = 2,
        severeOutOfRangeCm: Float = 3.5,
        maxMissingFaceFrames: Int = 5
    ) {
        self.targetCm = targetCm
        self.toleranceCm = toleranceCm
        self.armHoldMs = armHoldMs
        self.maxOutOfRangeFrames = maxOutOfRangeFrames
        self.severeOutOfRangeCm = severeOutOfRangeCm
        self.maxMissingFaceFrames = maxMissingFaceFrames
    }

    mutating func reset() {
        recentDistances.removeAll(keepingCapacity: true)
        armStableStartMs = nil
        outOfRangeStreak = 0
        missingFaceStreak = 0
    }

    mutating func beginRecordingWindow() {
        if let last = recentDistances.last {
            recentDistances = [last]
        }
        armStableStartMs = nil
        outOfRangeStreak = 0
        missingFaceStreak = 0
    }

    /// 3 帧快速中位数滤波：抑制单帧 MediaPipe 跳动，同时确保真实距离偏离超过 2 cm 在 ~100 ms 内立即触发中断。
    private mutating func pushAndFilter(_ rawDistanceCm: Float?) -> Float? {
        guard let d = rawDistanceCm, d.isFinite, d > 8, d < 120 else { return nil }
        recentDistances.append(d)
        if recentDistances.count > 3 {
            recentDistances.removeFirst(recentDistances.count - 3)
        }
        let sorted = recentDistances.sorted()
        if sorted.count == 2 {
            return (sorted[0] + sorted[1]) * 0.5
        }
        return sorted[sorted.count / 2]
    }

    mutating func evaluateArming(faceDetected: Bool, rawDistanceCm: Float?, nowMs: Int64) -> ArmUpdate {
        guard faceDetected, let dist = pushAndFilter(rawDistanceCm) else {
            armStableStartMs = nil
            recentDistances.removeAll(keepingCapacity: true)
            return .waiting(distanceCm: nil, inRange: false, holdProgress: 0, hint: "请将双眼对准前置镜头")
        }
        let delta = dist - targetCm
        if abs(delta) <= toleranceCm {
            if armStableStartMs == nil { armStableStartMs = nowMs }
            let elapsed = max(0, nowMs - (armStableStartMs ?? nowMs))
            let progress = min(1.0, Double(elapsed) / Double(max(1, armHoldMs)))
            if elapsed >= armHoldMs {
                return .readyToStart(distanceCm: dist)
            }
            return .waiting(distanceCm: dist, inRange: true, holdProgress: progress, hint: "距离到位，请保持不动…")
        } else {
            armStableStartMs = nil
            let hint = delta < 0
                ? String(format: "偏近 %.1f cm，请拿远至 %d±2 cm", abs(delta), Int(targetCm))
                : String(format: "偏远 %.1f cm，请拿近至 %d±2 cm", abs(delta), Int(targetCm))
            return .waiting(distanceCm: dist, inRange: false, holdProgress: 0, hint: hint)
        }
    }

    mutating func evaluateRecording(faceDetected: Bool, rawDistanceCm: Float?) -> RecordUpdate {
        guard faceDetected, let dist = pushAndFilter(rawDistanceCm) else {
            missingFaceStreak += 1
            if missingFaceStreak >= maxMissingFaceFrames {
                return .abort(
                    reason: "采集中人脸丢失，已自动重来",
                    speech: "未检测到人脸，已重置，请重新对准"
                )
            }
            let fallback = recentDistances.last ?? targetCm
            return .ok(distanceCm: fallback, deltaCm: fallback - targetCm)
        }
        missingFaceStreak = 0
        let delta = dist - targetCm
        let absErr = abs(delta)
        if absErr > toleranceCm {
            outOfRangeStreak += 1
            if absErr >= severeOutOfRangeCm || outOfRangeStreak >= maxOutOfRangeFrames {
                let dir = delta < 0 ? "偏近" : "偏远"
                let speech = delta < 0
                    ? "距离偏近超过两厘米，已重置，请拿远一点"
                    : "距离偏远超过两厘米，已重置，请拿近一点"
                return .abort(
                    reason: String(format: "距离%@（%.1f cm，超 ±%.0f cm），已自动重来", dir, dist, toleranceCm),
                    speech: speech
                )
            }
        } else {
            outOfRangeStreak = 0
        }
        return .ok(distanceCm: dist, deltaCm: delta)
    }
}

/// 纯 Swift 零依赖无压缩标准 ZIP（Stored, Method 0）归档器。
/// 视频 MP4 本身已是高度压缩格式，Stored 模式既能零内存峰值秒级打包整个数据集，又能被 Windows / macOS / Python `zipfile` 100% 原生解压。
enum StrabismusDatasetArchive {
    private static let crcTable: [UInt32] = {
        (0..<256).map { idx -> UInt32 in
            var c = UInt32(idx)
            for _ in 0..<8 {
                c = (c & 1) != 0 ? (0xEDB88320 ^ (c >> 1)) : (c >> 1)
            }
            return c
        }
    }()

    static func crc32(ofFile url: URL) throws -> (crc: UInt32, size: UInt32) {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var crc: UInt32 = 0xFFFF_FFFF
        var totalSize: UInt64 = 0
        while true {
            let chunk = autoreleasepool { handle.readData(ofLength: 256 * 1024) }
            if chunk.isEmpty { break }
            totalSize += UInt64(chunk.count)
            chunk.withUnsafeBytes { raw in
                guard let ptr = raw.bindMemory(to: UInt8.self).baseAddress else { return }
                for i in 0..<chunk.count {
                    let idx = Int((crc ^ UInt32(ptr[i])) & 0xFF)
                    crc = crcTable[idx] ^ (crc >> 8)
                }
            }
        }
        return (crc ^ 0xFFFF_FFFF, UInt32(truncatingIfNeeded: totalSize))
    }

    /// 将多个相对路径文件流式写入标准 ZIP 文件（`entries` 为 `(zip内部相对路径, 本地文件URL)`）。
    static func writeZip(entries: [(relativePath: String, fileURL: URL)], to outputURL: URL) throws {
        let fm = FileManager.default
        if fm.fileExists(atPath: outputURL.path) {
            try fm.removeItem(at: outputURL)
        }
        try fm.createDirectory(at: outputURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        fm.createFile(atPath: outputURL.path, contents: nil)
        let out = try FileHandle(forWritingTo: outputURL)
        defer { try? out.close() }

        struct CentralEntry {
            let nameData: Data
            let crc: UInt32
            let size: UInt32
            let localHeaderOffset: UInt32
        }

        var central: [CentralEntry] = []
        var offset: UInt64 = 0

        for entry in entries {
            let normalized = entry.relativePath.replacingOccurrences(of: "\\", with: "/")
            guard let nameData = normalized.data(using: .utf8) else { continue }
            let (crc, size) = try crc32(ofFile: entry.fileURL)
            let localOffset = UInt32(truncatingIfNeeded: offset)

            var localHeader = Data()
            localHeader.appendLE(UInt32(0x0403_4B50)) // Local file header signature
            localHeader.appendLE(UInt16(20))          // Version needed (2.0)
            localHeader.appendLE(UInt16(0x0800))      // UTF-8 filename flag
            localHeader.appendLE(UInt16(0))           // Compression method: 0 (Stored)
            localHeader.appendLE(UInt16(0))           // Mod time
            localHeader.appendLE(UInt16(0))           // Mod date
            localHeader.appendLE(crc)
            localHeader.appendLE(size)                // Compressed size
            localHeader.appendLE(size)                // Uncompressed size
            localHeader.appendLE(UInt16(nameData.count))
            localHeader.appendLE(UInt16(0))           // Extra length
            localHeader.append(nameData)

            out.write(localHeader)
            offset += UInt64(localHeader.count)

            let reader = try FileHandle(forReadingFrom: entry.fileURL)
            defer { try? reader.close() }
            while true {
                let chunk = autoreleasepool { reader.readData(ofLength: 256 * 1024) }
                if chunk.isEmpty { break }
                out.write(chunk)
                offset += UInt64(chunk.count)
            }

            central.append(CentralEntry(nameData: nameData, crc: crc, size: size, localHeaderOffset: localOffset))
        }

        let centralStartOffset = UInt32(truncatingIfNeeded: offset)
        var centralData = Data()
        for item in central {
            centralData.appendLE(UInt32(0x0201_4B50)) // Central directory signature
            centralData.appendLE(UInt16(20))          // Version made by
            centralData.appendLE(UInt16(20))          // Version needed
            centralData.appendLE(UInt16(0x0800))      // UTF-8 flag
            centralData.appendLE(UInt16(0))           // Stored
            centralData.appendLE(UInt16(0))
            centralData.appendLE(UInt16(0))
            centralData.appendLE(item.crc)
            centralData.appendLE(item.size)
            centralData.appendLE(item.size)
            centralData.appendLE(UInt16(item.nameData.count))
            centralData.appendLE(UInt16(0))           // Extra length
            centralData.appendLE(UInt16(0))           // Comment length
            centralData.appendLE(UInt16(0))           // Disk number start
            centralData.appendLE(UInt16(0))           // Internal attrs
            centralData.appendLE(UInt32(0))           // External attrs
            centralData.appendLE(item.localHeaderOffset)
            centralData.append(item.nameData)
        }
        out.write(centralData)

        var eocd = Data()
        eocd.appendLE(UInt32(0x0605_4B50))            // End of central directory signature
        eocd.appendLE(UInt16(0))                      // Disk number
        eocd.appendLE(UInt16(0))                      // Start disk
        eocd.appendLE(UInt16(central.count))          // Entries on disk
        eocd.appendLE(UInt16(central.count))          // Total entries
        eocd.appendLE(UInt32(centralData.count))      // Central directory size
        eocd.appendLE(centralStartOffset)             // Central directory offset
        eocd.appendLE(UInt16(0))                      // Comment length
        out.write(eocd)
    }

    /// 递归收集一个或多个样本目录内的全部文件，生成用于打包的相对路径列表。
    static func collectEntries(from sampleDirectories: [URL], rootFolderName: String) -> [(relativePath: String, fileURL: URL)] {
        let fm = FileManager.default
        var result: [(String, URL)] = []
        for dir in sampleDirectories {
            let folderName = dir.lastPathComponent
            guard let files = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.isRegularFileKey]) else { continue }
            for file in files.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                let isRegular = (try? file.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) ?? false
                guard isRegular else { continue }
                let rel = rootFolderName.isEmpty
                    ? "\(folderName)/\(file.lastPathComponent)"
                    : "\(rootFolderName)/\(folderName)/\(file.lastPathComponent)"
                result.append((rel, file))
            }
        }
        return result
    }
}

private extension Data {
    mutating func appendLE(_ value: UInt16) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }

    mutating func appendLE(_ value: UInt32) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }
}
