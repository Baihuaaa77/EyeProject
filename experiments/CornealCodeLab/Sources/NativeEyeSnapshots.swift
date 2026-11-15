import UIKit

/// 原生彩色 PNG 直接来自相机 BGRA，不经过视频有损编码。只保留选中的参考图，删除临时像素文件。
enum NativeEyeSnapshots {
    static func write(directory: URL,records: [FrameRecord],results: [EyeAnalysis],start: Double) throws -> Int {
        let url = directory.appendingPathComponent("temporary_eye_pixels.bgra")
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close(); try? FileManager.default.removeItem(at: url) }
        var metadata: [String:Any] = [:]
        for eye in ["image_left","image_right"] {
            let rows = records.filter { $0.eye == eye && $0.status == "valid" && $0.nativeOffset != nil && $0.nativeROI != nil }
            let result = results.first { $0.eye == eye }, time = result?.referenceHostTime ?? rows.last?.hostTime ?? 0
            guard let row = rows.min(by: { abs($0.hostTime-time)<abs($1.hostTime-time) }),let offset = row.nativeOffset,let native = row.nativeROI,let roi = row.roi else { continue }
            let w = Int(native.width), h = Int(native.height)
            try file.seek(toOffset: UInt64(offset))
            guard let bytes = try file.read(upToCount: w*h*4),bytes.count == w*h*4,let provider = CGDataProvider(data: bytes as CFData),
                  let cg = CGImage(width: w,height: h,bitsPerComponent: 8,bitsPerPixel: 32,bytesPerRow: w*4,space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue|CGBitmapInfo.byteOrder32Little.rawValue),provider: provider,decode: nil,shouldInterpolate: false,intent: .defaultIntent) else { throw EyeMediaExporter.failure("原生眼区像素不完整") }
            let image = UIImage(cgImage: cg)
            try image.pngData()?.write(to: directory.appendingPathComponent(eye+"_original.png"))
            let format = UIGraphicsImageRendererFormat(); format.scale = 1
            let size = EyeMediaExporter.size
            let annotated = UIGraphicsImageRenderer(size: size,format: format).image { graphics in
                image.draw(in: CGRect(origin: .zero,size: size))
                let ctx = graphics.cgContext
                // 分析 ROI 允许小数；原生像素裁剪取整，叠加时补偿原点和尺寸差。
                ctx.translateBy(x: (roi.x-native.x)/native.width*size.width,y: (roi.y-native.y)/native.height*size.height)
                ctx.scaleBy(x: roi.width/native.width,y: roi.height/native.height)
                EyeMediaExporter.overlay(ctx,size: size,row: row,result: result,elapsed: row.hostTime-start)
            }
            try annotated.pngData()?.write(to: directory.appendingPathComponent(eye+"_overlay.png"))
            metadata[eye] = ["width": w,"height": h,"hostTime": row.hostTime,"analysisROI": [roi.x,roi.y,roi.width,roi.height],"nativeROI": [native.x,native.y,native.width,native.height]]
        }
        metadata["source"] = "相机原生BGRA眼区，无视频压缩；PNG无损保存；没有增加光学分辨率"
        try JSONSerialization.data(withJSONObject: metadata,options: [.prettyPrinted,.sortedKeys]).write(to: directory.appendingPathComponent("native_snapshots.json"))
        return metadata.count-1
    }
}
