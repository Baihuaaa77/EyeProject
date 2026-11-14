import AVFoundation
import UIKit
import CoreImage

/// 从 camera.mov 派生彩色眼区资料；原视频不覆盖，分析用的128×64灰度区不冒充彩色原图。
enum EyeMediaExporter {
    static let size = CGSize(width: 640,height: 320)
    struct Stream {
        let writer: AVAssetWriter
        let input: AVAssetWriterInput
        let adaptor: AVAssetWriterInputPixelBufferAdaptor
        let eye: String
        let overlay: Bool
    }
    static func crop(_ image: CIImage,roi: LabRect) -> CIImage {
        let rect = CGRect(x: roi.x,y: image.extent.height-roi.y-roi.height,width: roi.width,height: roi.height).intersection(image.extent)
        let cropped = image.cropped(to: rect)
        return cropped.transformed(by: CGAffineTransform(translationX: -rect.minX,y: -rect.minY))
    }
    static func nearest(_ time: Double,rows: [FrameRecord],origin: Double) -> FrameRecord? {
        guard !rows.isEmpty else { return nil }
        var lo = 0, hi = rows.count
        while lo<hi { let m = (lo+hi)/2; if rows[m].pts-origin<=time { lo=m+1 } else { hi=m } }
        return rows[max(0,lo-1)]
    }
    static func streams(directory: URL,eye: String) throws -> [Stream] {
        try [false,true].map { overlay in
            let url = directory.appendingPathComponent(eye+(overlay ? "_overlay.mov" : "_original.mov"))
            let writer = try AVAssetWriter(outputURL: url,fileType: .mov)
            let input = AVAssetWriterInput(mediaType: .video,outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: Int(size.width),AVVideoHeightKey: Int(size.height),AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 3_000_000]])
            input.expectsMediaDataInRealTime = false
            let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input,sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,kCVPixelBufferWidthKey as String: Int(size.width),
                kCVPixelBufferHeightKey as String: Int(size.height),kCVPixelBufferCGImageCompatibilityKey as String: true,kCVPixelBufferCGBitmapContextCompatibilityKey as String: true])
            guard writer.canAdd(input) else { throw failure("无法创建眼区视频编码器") }
            writer.add(input)
            guard writer.startWriting() else { throw writer.error ?? failure("眼区视频写入启动失败") }
            return Stream(writer: writer,input: input,adaptor: adaptor,eye: eye,overlay: overlay)
        }
    }
    static func failure(_ message: String) -> NSError { NSError(domain: "EyeMedia",code: 1,userInfo: [NSLocalizedDescriptionKey: message]) }

    static func export(directory: URL,records: [FrameRecord],results: [EyeAnalysis],start: Double,videoStartPTS: Double) throws -> [String] {
        let asset = AVURLAsset(url: directory.appendingPathComponent("camera.mov"))
        guard let track = asset.tracks(withMediaType: .video).first else { throw failure("原始视频没有画面轨道") }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track,outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        output.alwaysCopiesSampleData = false; reader.add(output)
        var eyeRows: [String:[FrameRecord]] = [:], qualityRows: [String:[FrameRecord]] = [:], writers: [Stream] = []
        for eye in ["image_left","image_right"] {
            qualityRows[eye] = records.filter { $0.eye == nil || $0.eye == eye }.sorted { $0.pts<$1.pts }
            let rows = records.filter { $0.eye == eye && $0.roi != nil }.sorted { $0.pts<$1.pts }
            if !rows.isEmpty { eyeRows[eye] = rows; writers += try streams(directory: directory,eye: eye) }
        }
        guard !writers.isEmpty else { throw failure("没有可用的虹膜关键点，无法生成眼区裁剪；原始全画面视频已保留") }
        var complete = false
        defer { if !complete { reader.cancelReading(); writers.forEach { $0.writer.cancelWriting() } } }
        guard reader.startReading() else { throw reader.error ?? failure("无法读取原始视频") }
        let context = CIContext(), origin = CMTimeGetSeconds(track.timeRange.start)
        var firstPTS: CMTime?, bestReference: [String:Double] = [:], frameCounts: [String:Int] = [:]
        while let sample = output.copyNextSampleBuffer() {
            try autoreleasepool {
                guard let buffer = CMSampleBufferGetImageBuffer(sample) else { return }
                let pts = CMSampleBufferGetPresentationTimeStamp(sample), t = CMTimeGetSeconds(pts)-origin
                if firstPTS == nil { firstPTS = pts; writers.forEach { $0.writer.startSession(atSourceTime: pts) } }
                let image = CIImage(cvPixelBuffer: buffer)
                for eye in ["image_left","image_right"] {
                    guard let rows = eyeRows[eye],let row = nearest(t,rows: rows,origin: videoStartPTS),let roi = row.roi else { continue }
                    let cropped = crop(image,roi: roi)
                    let scaled = cropped.transformed(by: CGAffineTransform(scaleX: size.width/cropped.extent.width,y: size.height/cropped.extent.height))
                    let result = results.first { $0.eye == eye }
                    let latestStatus = nearest(t,rows: qualityRows[eye] ?? [],origin: videoStartPTS)?.status
                    let age = t-(row.pts-videoStartPTS)
                    let valid = age>=(-0.001) && age<=0.12 && row.status == "valid" && (latestStatus == "valid" || latestStatus == "not_analyzed")
                    for stream in writers where stream.eye == eye {
                        let deadline = Date().addingTimeInterval(5)
                        while !stream.input.isReadyForMoreMediaData {
                            if Date()>deadline || stream.writer.status != .writing { throw stream.writer.error ?? failure("眼区视频编码器超时") }
                            Thread.sleep(forTimeInterval: 0.001)
                        }
                        var pixel: CVPixelBuffer?
                        guard let pool = stream.adaptor.pixelBufferPool,CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault,pool,&pixel) == kCVReturnSuccess,let pixel = pixel else { throw failure("眼区视频缓冲不足") }
                        context.render(scaled,to: pixel)
                        if stream.overlay,valid { draw(on: pixel,row: row,result: result,elapsed: row.hostTime-start) }
                        guard stream.adaptor.append(pixel,withPresentationTime: pts) else { throw stream.writer.error ?? failure("眼区视频写入失败") }
                    }
                    frameCounts[eye,default: 0] += 1
                    let reference = result?.referenceHostTime ?? rows.last!.hostTime
                    let target = rows.min { abs($0.hostTime-reference)<abs($1.hostTime-reference) }!.pts-videoStartPTS
                    let difference = abs(t-target)
                    if bestReference[eye] == nil,difference<=0.02,let cg = context.createCGImage(cropped,from: cropped.extent) {
                        bestReference[eye] = difference
                        try UIImage(cgImage: cg).pngData()?.write(to: directory.appendingPathComponent(eye+"_original.png"))
                        let format = UIGraphicsImageRendererFormat(); format.scale = 1
                        let annotated = UIGraphicsImageRenderer(size: size,format: format).image { graphics in
                            UIImage(cgImage: cg).draw(in: CGRect(origin: .zero,size: size))
                            if valid { overlay(graphics.cgContext,size: size,row: row,result: result,elapsed: row.hostTime-start) }
                        }
                        try annotated.pngData()?.write(to: directory.appendingPathComponent(eye+"_overlay.png"))
                    }
                }
            }
        }
        guard reader.status == .completed else { throw reader.error ?? failure("原视频读取未完成") }
        let group = DispatchGroup()
        for stream in writers { stream.input.markAsFinished(); group.enter(); stream.writer.finishWriting { group.leave() } }
        guard group.wait(timeout: .now()+30) == .success,writers.allSatisfy({ $0.writer.status == .completed }) else { throw failure("眼区视频未成功完成") }
        let media: [String:Any] = ["输出尺寸": [640,320],"帧数": frameCounts,"说明": "彩色原图为原视频原生像素裁剪；眼区视频裁剪后放大并以H.264重新编码，不增加光学分辨率。关键点丢失时沿用最近裁剪窗口，隐藏检测叠加。", "参考图与目标时间差秒": bestReference]
        try JSONSerialization.data(withJSONObject: media,options: [.prettyPrinted,.sortedKeys]).write(to: directory.appendingPathComponent("eye_media.json"))
        complete = true
        return writers.map { $0.eye+($0.overlay ? "_overlay.mov" : "_original.mov") }
    }

    static func draw(on buffer: CVPixelBuffer,row: FrameRecord,result: EyeAnalysis?,elapsed: Double) {
        CVPixelBufferLockBaseAddress(buffer,[]); defer { CVPixelBufferUnlockBaseAddress(buffer,[]) }
        guard let bytes = CVPixelBufferGetBaseAddress(buffer),let ctx = CGContext(data: bytes,width: Int(size.width),height: Int(size.height),bitsPerComponent: 8,bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),space: CGColorSpaceCreateDeviceRGB(),bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue|CGBitmapInfo.byteOrder32Little.rawValue) else { return }
        ctx.translateBy(x: 0,y: size.height); ctx.scaleBy(x: 1,y: -1)
        overlay(ctx,size: size,row: row,result: result,elapsed: elapsed)
    }
    static func overlay(_ ctx: CGContext,size: CGSize,row: FrameRecord,result: EyeAnalysis?,elapsed: Double) {
        func ring(_ p: LabPoint,_ radius: Double,_ color: UIColor) {
            ctx.setStrokeColor(color.cgColor); ctx.setLineWidth(2)
            ctx.strokeEllipse(in: CGRect(x: p.x*size.width-radius,y: p.y*size.height-radius,width: radius*2,height: radius*2))
        }
        if let iris = row.iris {
            ctx.setStrokeColor(UIColor(LabStyle.primary).cgColor); ctx.setLineWidth(2)
            ctx.strokeEllipse(in: CGRect(x: (iris.center.x-(iris.boundaryRadiusX ?? iris.radiusX))*size.width,y: (iris.center.y-(iris.boundaryRadiusY ?? iris.radiusY))*size.height,width: (iris.boundaryRadiusX ?? iris.radiusX)*2*size.width,height: (iris.boundaryRadiusY ?? iris.radiusY)*2*size.height))
            ctx.setLineDash(phase: 0,lengths: [5,4])
            ctx.strokeEllipse(in: CGRect(x: (iris.center.x-iris.radiusX)*size.width,y: (iris.center.y-iris.radiusY)*size.height,width: iris.radiusX*2*size.width,height: iris.radiusY*2*size.height))
            ctx.setLineDash(phase: 0,lengths: [])
        }
        if let pupil = row.baselinePupil { ring(pupil,3,UIColor(LabStyle.landmark)) }
        if let point = row.rawCandidate { ring(point,6,UIColor(LabStyle.baseline)) }
        if let motion = result?.motionResults {
            for source in motion {
                if let o = source.observations.min(by: { abs($0.time-elapsed)<abs($1.time-elapsed) }),abs(o.time-elapsed)<=0.12 {
                    let p = LabPoint(x: o.point.x/Double(result?.patchWidth ?? 128),y: o.point.y/Double((result?.patchWidth ?? 128)/2))
                    if row.iris?.contains(p) == true { ring(p,10,UIColor(source.accepted ? LabStyle.coded : LabStyle.candidate)) }
                }
            }
        } else if result?.codingApplied == true,let point = result?.point,row.iris?.contains(point) == true {
            ring(point,10,UIColor(result?.codedEvidence == true ? LabStyle.coded : LabStyle.candidate))
        }
    }
}
