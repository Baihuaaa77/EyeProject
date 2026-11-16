import Foundation
#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

/// 单个 JSON 包含全部结构化记录；可选将像素二进制编码到同一文件，供完整复算。
enum TrialDataExport {
    static func write(directory: URL,includePixels: Bool = true) throws -> URL {
        var documents: [String:Any] = [:]
        let files = try FileManager.default.contentsOfDirectory(at: directory,includingPropertiesForKeys: nil).sorted { $0.lastPathComponent<$1.lastPathComponent }
        for url in files where url.pathExtension == "json" && !url.lastPathComponent.hasPrefix("实验数据") {
            documents[url.lastPathComponent] = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
        }
        let frames = directory.appendingPathComponent("frames.jsonl")
        if FileManager.default.fileExists(atPath: frames.path) {
            documents["frames.jsonl"] = try String(contentsOf: frames,encoding: .utf8).split(whereSeparator: \.isNewline).map {
                try JSONSerialization.jsonObject(with: Data($0.utf8))
            }
        }
        let output = directory.appendingPathComponent(includePixels ? "实验数据_完整.json" : "实验数据_轻量.json")
        let temporary = directory.appendingPathComponent(UUID().uuidString+".tmp")
        guard FileManager.default.createFile(atPath: temporary.path,contents: nil) else {
            throw NSError(domain: "TrialDataExport",code: 1,userInfo: [NSLocalizedDescriptionKey:"无法创建数据导出文件"])
        }
        let file = try FileHandle(forWritingTo: temporary)
        do {
            let header: [String:Any] = ["format":"corneal-lab-bundle-1","documents":documents,
                "pixelEncoding":"base64","includesPixels":includePixels,
                "说明":"结构化数据含逐帧跟踪合为一个JSON；完整文件内嵌原128×64分析像素，可无损恢复。原生分辨率跟踪复算还需单独导出的camera.mov；视频与PNG由各自按钮分享。"]
            var bytes = try JSONSerialization.data(withJSONObject: header,options: [.sortedKeys])
            bytes.removeLast()
            try file.write(contentsOf: bytes); try file.write(contentsOf: Data(",\"binaryFiles\":{".utf8))
            var first = true
            if includePixels {
                for name in ["patches.f32","patches_rgb.u8"] {
                    let url = directory.appendingPathComponent(name)
                    guard FileManager.default.fileExists(atPath: url.path) else { continue }
                    if !first { try file.write(contentsOf: Data(",".utf8)) }; first = false
                    try file.write(contentsOf: Data(("\""+name+"\":\"").utf8))
                    let input = try FileHandle(forReadingFrom: url)
                    defer { try? input.close() }
                    // 3的倍数保证各块的Base64直接拼接后仍可无损解码；末块才有填充。
                    var pending = Data()
                    while let block = try input.read(upToCount: 3*256*1024),!block.isEmpty {
                        pending.append(block)
                        let length = pending.count-pending.count%3
                        if length>0 {
                            try file.write(contentsOf: Data(pending.prefix(length).base64EncodedString().utf8))
                            pending = Data(pending.suffix(pending.count-length))
                        }
                    }
                    if !pending.isEmpty { try file.write(contentsOf: Data(pending.base64EncodedString().utf8)) }
                    try file.write(contentsOf: Data("\"".utf8))
                }
            }
            try file.write(contentsOf: Data("}}".utf8)); try file.close()
            // 同目录POSIX rename原子替换，兼容手机、macOS及Linux离线工具。
            let code = temporary.path.withCString { source in output.path.withCString { destination in rename(source,destination) } }
            if code != 0 { throw NSError(domain: NSPOSIXErrorDomain,code: Int(errno)) }
            return output
        } catch { try? file.close(); try? FileManager.default.removeItem(at: temporary); throw error }
    }
}
