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
        let size: CGSize
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
            try stream(directory: directory,name: eye+(overlay ? "_overlay.mov" : "_original.mov"),eye: eye,overlay: overlay,size: size)
        }
    }
    static func stream(directory: URL,name: String,eye: String,overlay: Bool,size: CGSize) throws -> Stream {
            let url = directory.appendingPathComponent(name)
            let writer = try AVAssetWriter(outputURL: url,fileType: .mov)
            let input = AVAssetWriterInput(mediaType: .video,outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: Int(size.width),AVVideoHeightKey: Int(size.height),AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: max(3_000_000,Int(size.width*size.height*4))]])
            input.expectsMediaDataInRealTime = false
            let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input,sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,kCVPixelBufferWidthKey as String: Int(size.width),
                kCVPixelBufferHeightKey as String: Int(size.height),kCVPixelBufferCGImageCompatibilityKey as String: true,kCVPixelBufferCGBitmapContextCompatibilityKey as String: true])
            guard writer.canAdd(input) else { throw failure("无法创建眼区视频编码器") }
            writer.add(input)
            guard writer.startWriting() else { throw writer.error ?? failure("眼区视频写入启动失败") }
            return Stream(writer: writer,input: input,adaptor: adaptor,eye: eye,overlay: overlay,size: size)
    }
    static func failure(_ message: String) -> NSError { NSError(domain: "EyeMedia",code: 1,userInfo: [NSLocalizedDescriptionKey: message]) }

    static func export(directory: URL,records: [FrameRecord],results: [EyeAnalysis],start: Double,videoStartPTS: Double,trackingOnly: Bool = false,outputDirectory: URL? = nil) throws -> [String] {
        let destination = outputDirectory ?? directory
        let fullName = trackingOnly ? "camera_tracking.mov" : "camera_overlay.mov"
        let asset = AVURLAsset(url: directory.appendingPathComponent("camera.mov"))
        guard let track = asset.tracks(withMediaType: .video).first else { throw failure("原始视频没有画面轨道") }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track,outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        output.alwaysCopiesSampleData = false; reader.add(output)
        var eyeRows: [String:[FrameRecord]] = [:], qualityRows: [String:[FrameRecord]] = [:], writers: [Stream] = []
        for eye in ["image_left","image_right"] {
            qualityRows[eye] = records.filter { $0.eye == nil || $0.eye == eye }.sorted { $0.pts<$1.pts }
            let rows = records.filter { $0.eye == eye && $0.roi != nil }.sorted { $0.pts<$1.pts }
            if !rows.isEmpty && !trackingOnly { eyeRows[eye] = rows; writers += try streams(directory: destination,eye: eye) }
        }
        let fullSize = track.naturalSize
        writers.append(try stream(directory: destination,name: fullName,eye: "full",overlay: true,size: fullSize))
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
                        let enlarged = UIGraphicsImageRenderer(size: size,format: format).image { graphics in
                            graphics.cgContext.interpolationQuality = .none
                            UIImage(cgImage: cg).draw(in: CGRect(origin: .zero,size: size))
                        }
                        try enlarged.pngData()?.write(to: directory.appendingPathComponent(eye+"_enlarged.png"))
                        let annotated = UIGraphicsImageRenderer(size: size,format: format).image { graphics in
                            UIImage(cgImage: cg).draw(in: CGRect(origin: .zero,size: size))
                            if valid { overlay(graphics.cgContext,size: size,row: row,result: result,elapsed: row.hostTime-start) }
                        }
                        try annotated.pngData()?.write(to: directory.appendingPathComponent(eye+"_overlay.png"))
                    }
                }
                if let stream = writers.first(where: { $0.eye == "full" }) {
                    let deadline = Date().addingTimeInterval(5)
                    while !stream.input.isReadyForMoreMediaData {
                        if Date()>deadline || stream.writer.status != .writing { throw stream.writer.error ?? failure("全画面叠加视频编码超时") }
                        Thread.sleep(forTimeInterval: 0.001)
                    }
                    var pixel: CVPixelBuffer?
                    guard let pool = stream.adaptor.pixelBufferPool,CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault,pool,&pixel) == kCVReturnSuccess,let pixel = pixel else { throw failure("全画面视频缓冲不足") }
                    context.render(image,to: pixel)
                    let current = ["image_left","image_right"].compactMap { eye in nearest(t,rows: qualityRows[eye] ?? [],origin: videoStartPTS) }
                    drawFull(on: pixel,rows: current,results: results,videoTime: t,videoStartPTS: videoStartPTS,start: start)
                    guard stream.adaptor.append(pixel,withPresentationTime: pts) else { throw stream.writer.error ?? failure("全画面叠加视频写入失败") }
                }
            }
        }
        guard reader.status == .completed else { throw reader.error ?? failure("原视频读取未完成") }
        let group = DispatchGroup()
        for stream in writers { stream.input.markAsFinished(); group.enter(); stream.writer.finishWriting { group.leave() } }
        guard group.wait(timeout: .now()+30) == .success,writers.allSatisfy({ $0.writer.status == .completed }) else { throw failure("眼区视频未成功完成") }
        let media: [String:Any] = ["输出尺寸": [640,320],"帧数": frameCounts,"说明": "彩色原图为原视频原生像素裁剪；眼区视频裁剪后放大并以H.264重新编码，不增加光学分辨率。关键点丢失时沿用最近裁剪窗口，隐藏检测叠加。", "参考图与目标时间差秒": bestReference]
        try JSONSerialization.data(withJSONObject: media,options: [.prettyPrinted,.sortedKeys]).write(to: destination.appendingPathComponent(trackingOnly ? "tracking_media.json" : "eye_media.json"))
        complete = true
        return writers.map { $0.eye == "full" ? fullName : $0.eye+($0.overlay ? "_overlay.mov" : "_original.mov") }
    }

    static func drawFull(on buffer: CVPixelBuffer,rows: [FrameRecord],results: [EyeAnalysis],videoTime: Double,videoStartPTS: Double,start: Double) {
        CVPixelBufferLockBaseAddress(buffer,[]); defer { CVPixelBufferUnlockBaseAddress(buffer,[]) }
        let w = CVPixelBufferGetWidth(buffer),h = CVPixelBufferGetHeight(buffer)
        guard let bytes = CVPixelBufferGetBaseAddress(buffer),let ctx = CGContext(data: bytes,width: w,height: h,bitsPerComponent: 8,bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),space: CGColorSpaceCreateDeviceRGB(),bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue|CGBitmapInfo.byteOrder32Little.rawValue) else { return }
        ctx.translateBy(x: 0,y: CGFloat(h)); ctx.scaleBy(x: 1,y: -1)
        fullOverlay(ctx,canvasSize: CGSize(width: w,height: h),rows: rows,results: results,videoTime: videoTime,videoStartPTS: videoStartPTS,start: start)
    }

    /// 播放器和导出复用同一坐标转换；拒判帧及超过120ms的记录不显示历史标记。
    static func fullOverlay(_ ctx: CGContext,canvasSize: CGSize,rows: [FrameRecord],results: [EyeAnalysis],videoTime: Double,videoStartPTS: Double,start: Double) {
        for row in rows {
            let age = videoTime-(row.pts-videoStartPTS)
            guard row.status == "valid",age>=(-0.001),age<=0.12,let eye = row.eye,let roi = row.roi,row.width>0,row.height>0 else { continue }
            ctx.saveGState()
            ctx.scaleBy(x: canvasSize.width/CGFloat(row.width),y: canvasSize.height/CGFloat(row.height))
            ctx.translateBy(x: roi.x,y: roi.y)
            ctx.clip(to: CGRect(x: 0,y: 0,width: roi.width,height: roi.height))
            overlay(ctx,size: CGSize(width: roi.width,height: roi.height),row: row,result: results.first { $0.eye == eye },elapsed: row.hostTime-start,includeTracking: false)
            ctx.restoreGState()
        }
        guard let dimensions = rows.first,dimensions.width>0,dimensions.height>0 else { return }
        for result in results {
            guard let sample = result.tracking?.nearest(pts: videoStartPTS+videoTime),abs(sample.pts-videoStartPTS-videoTime)<=0.08,
                  let p = sample.imagePoint else { continue }
            ctx.saveGState()
            ctx.scaleBy(x: canvasSize.width/CGFloat(dimensions.width),y: canvasSize.height/CGFloat(dimensions.height))
            trackingMark(ctx,point: CGPoint(x: p.x,y: p.y),sample: sample,font: LabStyle.overlayFullFont,scale: 2)
            ctx.restoreGState()
        }
    }

    static func draw(on buffer: CVPixelBuffer,row: FrameRecord,result: EyeAnalysis?,elapsed: Double) {
        CVPixelBufferLockBaseAddress(buffer,[]); defer { CVPixelBufferUnlockBaseAddress(buffer,[]) }
        guard let bytes = CVPixelBufferGetBaseAddress(buffer),let ctx = CGContext(data: bytes,width: Int(size.width),height: Int(size.height),bitsPerComponent: 8,bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),space: CGColorSpaceCreateDeviceRGB(),bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue|CGBitmapInfo.byteOrder32Little.rawValue) else { return }
        ctx.translateBy(x: 0,y: size.height); ctx.scaleBy(x: 1,y: -1)
        overlay(ctx,size: size,row: row,result: result,elapsed: elapsed)
    }
    static func overlay(_ ctx: CGContext,size: CGSize,row: FrameRecord,result: EyeAnalysis?,elapsed: Double,includeTracking: Bool = true) {
        func ring(_ p: LabPoint,_ radius: Double,_ color: UIColor) {
            ctx.setStrokeColor(color.cgColor); ctx.setLineWidth(2)
            ctx.strokeEllipse(in: CGRect(x: p.x*size.width-radius,y: p.y*size.height-radius,width: radius*2,height: radius*2))
        }
        if let iris = row.iris {
            ctx.setStrokeColor(UIColor(LabStyle.primary).cgColor); ctx.setLineWidth(2)
            ctx.strokeEllipse(in: CGRect(x: (iris.center.x-(iris.boundaryRadiusX ?? iris.radiusX))*size.width,y: (iris.center.y-(iris.boundaryRadiusY ?? iris.radiusY))*size.height,width: (iris.boundaryRadiusX ?? iris.radiusX)*2*size.width,height: (iris.boundaryRadiusY ?? iris.radiusY)*2*size.height))
            ctx.setLineDash(phase: 0,lengths: [5,4])
            let rx = result?.tracking == nil ? iris.radiusX : (iris.boundaryRadiusX ?? iris.radiusX)*0.8
            let ry = result?.tracking == nil ? iris.radiusY : (iris.boundaryRadiusY ?? iris.radiusY)*0.8
            ctx.strokeEllipse(in: CGRect(x: (iris.center.x-rx)*size.width,y: (iris.center.y-ry)*size.height,width: rx*2*size.width,height: ry*2*size.height))
            ctx.setLineDash(phase: 0,lengths: [])
        }
        if let pupil = row.baselinePupil { ring(pupil,3,UIColor(LabStyle.landmark)) }
        if let tracking = result?.tracking {
            if includeTracking,let sample = tracking.nearest(hostTime: row.hostTime),abs(sample.hostTime-row.hostTime)<=0.08,
               let p = sample.imagePoint,let roi = row.roi {
                trackingMark(ctx,point: CGPoint(x: (p.x-roi.x)/roi.width*size.width,y: (p.y-roi.y)/roi.height*size.height),sample: sample,font: LabStyle.overlayFont)
            }
            return
        }
        if let point = row.rawCandidate { ring(point,6,UIColor(LabStyle.baseline)) }
        if let screen = result?.screenResult,let observation = screen.observations.min(by: { abs($0.hostTime-row.hostTime)<abs($1.hostTime-row.hostTime) }),abs(observation.hostTime-row.hostTime)<=0.12 {
            ctx.setStrokeColor(UIColor(LabStyle.candidate).cgColor); ctx.setLineWidth(2)
            for (i,p) in observation.corners.enumerated() {
                let q = CGPoint(x: p.x*size.width,y: p.y*size.height)
                if i == 0 { ctx.move(to: q) } else { ctx.addLine(to: q) }
            }
            ctx.closePath(); ctx.strokePath()
            if let p = observation.darkPoint { ring(p,8,UIColor(screen.structuralEvidence ? LabStyle.coded : LabStyle.candidate)) }
        } else if let motion = result?.motionResults {
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

    static func trackingMark(_ ctx: CGContext,point: CGPoint,sample: GlintEstimate,font: UIFont,scale: CGFloat = 1) {
        let color = UIColor(sample.source == .observed ? LabStyle.coded : sample.source == .weak ? LabStyle.candidate : sample.source == .predicted ? LabStyle.baseline : LabStyle.secondary)
        ctx.saveGState(); defer { ctx.restoreGState() }
        ctx.setStrokeColor(color.cgColor); ctx.setLineWidth(2*scale)
        if !sample.source.measured { ctx.setLineDash(phase: 0,lengths: [4*scale,3*scale]) }
        let radius: CGFloat = 6*scale
        ctx.strokeEllipse(in: CGRect(x: point.x-radius,y: point.y-radius,width: radius*2,height: radius*2))
        ctx.setLineDash(phase: 0,lengths: [])
        let title = "\(sample.source.title) \(String(format: "%.2f",sample.confidence))"
        UIGraphicsPushContext(ctx)
        (title as NSString).draw(at: CGPoint(x: point.x+radius+4,y: point.y-radius-font.pointSize),withAttributes: [.font:font,.foregroundColor:color,.backgroundColor:UIColor(LabStyle.background)])
        UIGraphicsPopContext()
    }
}
