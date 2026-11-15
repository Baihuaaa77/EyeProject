import Foundation

/// 同一份 Swift 算法用于手机和离线复算；不借用旧结果的候选位置。
public enum CornealReplay {
    private struct Header: Decodable {
        var configuration: TrialConfiguration
        var startHostTime: Double
    }
    public static func run(directory: URL,output: URL) throws {
        let decoder = JSONDecoder()
        let summary = try decoder.decode(Header.self,from: Data(contentsOf: directory.appendingPathComponent("summary.json")))
        let events = try decoder.decode([DisplayEvent].self,from: Data(contentsOf: directory.appendingPathComponent("display_events.json")))
        let records = try String(contentsOf: directory.appendingPathComponent("frames.jsonl"),encoding: .utf8).split(whereSeparator: { $0.isNewline }).map { try decoder.decode(FrameRecord.self,from: Data($0.utf8)) }
        let gray = try Data(contentsOf: directory.appendingPathComponent("patches.f32"),options: .mappedIfSafe)
        let rgb = (try? Data(contentsOf: directory.appendingPathComponent("patches_rgb.u8"),options: .mappedIfSafe)) ?? Data()
        guard !FileManager.default.fileExists(atPath: output.path) else { throw error("输出文件已存在，请选择新文件名，避免覆盖实验资料") }
        guard summary.configuration.validationError == nil else { throw error(summary.configuration.validationError!) }
        var samples: [PatchSample] = []
        for r in records where r.status == "valid" {
            guard let eye = r.eye,let roi = r.roi,let pupil = r.pupil,let offset = r.patchOffset,offset>=0,offset<=gray.count-8192*4 else { throw error("缺少完整的 128×64 眼区或偏移越界；v1 的 64×32 数据请用 review.py") }
            var pixels = [Float](); pixels.reserveCapacity(8192)
            for i in 0..<8192 {
                let j = offset+i*4
                let bits = UInt32(gray[j]) | UInt32(gray[j+1])<<8 | UInt32(gray[j+2])<<16 | UInt32(gray[j+3])<<24
                pixels.append(Float(bitPattern: bits))
            }
            var color: [UInt8]?
            if let off = r.rgbPatchOffset {
                guard off>=0,off<=rgb.count-8192*3 else { throw error("彩色眼区偏移越界") }
                color = Array(rgb[off..<(off+8192*3)])
            }
            samples.append(PatchSample(eye: eye,hostTime: r.hostTime,pixels: pixels,roi: roi,pupil: pupil,rawCandidate: r.rawCandidate,
                saturation: r.saturation ?? 1,iris: r.iris,rgb: color,nuisance: r.nuisance,baselinePupil: r.baselinePupil,baselineReason: r.baselineReason))
        }
        let results = ["image_left","image_right"].map { CornealAnalyzer.analyze(eye: $0,samples: samples,events: events,start: summary.startHostTime,configuration: summary.configuration) }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted,.sortedKeys]
        try encoder.encode(results).write(to: output,options: .atomic)
    }
    private static func error(_ text: String) -> NSError { NSError(domain: "CornealReplay",code: 1,userInfo: [NSLocalizedDescriptionKey: text]) }
}
