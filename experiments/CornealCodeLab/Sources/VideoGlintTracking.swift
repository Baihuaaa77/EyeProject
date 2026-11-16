import AVFoundation
import Foundation

/// 旧轮次从原视频解码原生眼区复算，保留原始帧日志及旧结果。
enum VideoGlintTracking {
    static func prepare(directory: URL,records: [FrameRecord],videoStartPTS: Double,output: URL) throws -> [FrameRecord] {
        guard !FileManager.default.fileExists(atPath: output.path),FileManager.default.createFile(atPath: output.path,contents: nil) else {
            throw EyeMediaExporter.failure("无法创建复算临时像素文件")
        }
        let file = try FileHandle(forWritingTo: output); defer { try? file.close() }
        let asset = AVURLAsset(url: directory.appendingPathComponent("camera.mov"))
        guard let track = asset.tracks(withMediaType: .video).first else { throw EyeMediaExporter.failure("没有原视频") }
        let reader = try AVAssetReader(asset: asset)
        let decoded = AVAssetReaderTrackOutput(track: track,outputSettings: [kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_32BGRA])
        decoded.alwaysCopiesSampleData = false; reader.add(decoded)
        guard reader.startReading() else { throw reader.error ?? EyeMediaExporter.failure("原视频解码失败") }
        defer { if reader.status == .reading { reader.cancelReading() } }
        let grouped = Dictionary(grouping: records,by: \.index)
        let timeline = grouped.values.compactMap(\.first).sorted { $0.pts<$1.pts }
        let origin = CMTimeGetSeconds(track.timeRange.start)
        var rows: [FrameRecord] = [],offset = 0,seen = Set<Int>()
        while let sample = decoded.copyNextSampleBuffer() {
            try autoreleasepool {
                let seconds = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))-origin
                guard let buffer = CMSampleBufferGetImageBuffer(sample),let nearest = EyeMediaExporter.nearest(seconds+0.002,rows: timeline,origin: videoStartPTS),
                      abs(nearest.pts-videoStartPTS-seconds)<0.04,!seen.contains(nearest.index) else { return }
                seen.insert(nearest.index)
                CVPixelBufferLockBaseAddress(buffer,.readOnly); defer { CVPixelBufferUnlockBaseAddress(buffer,.readOnly) }
                guard let base = CVPixelBufferGetBaseAddress(buffer)?.assumingMemoryBound(to: UInt8.self) else { return }
                let width = CVPixelBufferGetWidth(buffer),height = CVPixelBufferGetHeight(buffer),stride = CVPixelBufferGetBytesPerRow(buffer)
                for original in grouped[nearest.index] ?? [] {
                    var row = original; row.nativeOffset = nil
                    if let roi = row.roi {
                        let x = Int(floor(roi.x)),y = Int(floor(roi.y))
                        let w = Int(ceil(roi.x+roi.width))-x,h = Int(ceil(roi.y+roi.height))-y
                        if x>=0,y>=0,w>=8,h>=8,x+w<=width,y+h<=height {
                            var gray = [UInt8](repeating: 0,count: w*h)
                            for yy in 0..<h { for xx in 0..<w {
                                let i = (y+yy)*stride+(x+xx)*4
                                let value = 0.0722*Double(base[i])+0.7152*Double(base[i+1])+0.2126*Double(base[i+2])
                                gray[yy*w+xx] = UInt8(min(255,max(0,value.rounded())))
                            } }
                            row.nativeOffset = offset; row.nativeROI = LabRect(x: Double(x),y: Double(y),width: Double(w),height: Double(h))
                            try file.write(contentsOf: Data(gray)); offset += gray.count
                        }
                    }
                    rows.append(row)
                }
            }
        }
        guard reader.status == .completed else { throw reader.error ?? EyeMediaExporter.failure("原视频读取未完成") }
        // 编码丢帧不制造像素证据，未出现在视频中的记录仍保留零置信度位置记录。
        rows += records.filter { !seen.contains($0.index) }.map { original in var row = original; row.nativeOffset = nil; return row }
        return rows.sorted { $0.hostTime<$1.hostTime }
    }
}
