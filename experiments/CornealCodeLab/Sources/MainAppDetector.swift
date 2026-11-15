import AVFoundation
import UIKit
import EyeScreening
import MediaPipeTasksVision
import CoreImage

/// 直接调用主 App 的 SDK 配置、像素适配器、478 点几何和 Kotlin 映光算法。
final class MainAppDetector {
    private var detector: FaceLandmarker
    private(set) var usingGPU = true
    private let context = CIContext(options: [.cacheIntermediates:false])
    private var inferenceBuffer: CVPixelBuffer?
    init() throws {
        do { detector = try EyeCamera.makeDetector(gpu: true) }
        catch { detector = try EyeCamera.makeDetector(gpu: false); usingGPU = false }
    }
    func analyze(_ buffer: CVPixelBuffer, hostTime: Double) throws -> FaceMetrics? {
        // 模型只需归一化关键点；限制推理输入，原生4K像素继续用于光学测量及录像。
        let width = CVPixelBufferGetWidth(buffer),height = CVPixelBufferGetHeight(buffer)
        let scale = min(1,1280/Double(max(width,height)))
        var input = buffer
        if scale<1 {
            let w = Int(Double(width)*scale),h = Int(Double(height)*scale)
            if inferenceBuffer == nil || CVPixelBufferGetWidth(inferenceBuffer!) != w || CVPixelBufferGetHeight(inferenceBuffer!) != h {
                inferenceBuffer = nil
                CVPixelBufferCreate(kCFAllocatorDefault,w,h,kCVPixelFormatType_32BGRA,
                    [kCVPixelBufferIOSurfacePropertiesKey as String: [:]] as CFDictionary,&inferenceBuffer)
            }
            if let reduced = inferenceBuffer {
                context.render(CIImage(cvPixelBuffer: buffer).transformed(by: CGAffineTransform(scaleX: scale,y: scale)),to: reduced)
                input = reduced
            }
        }
        let image = try MPImage(pixelBuffer: input,orientation: .up)
        let result: FaceLandmarkerResult
        do { result = try detector.detect(videoFrame: image,timestampInMilliseconds: Int(hostTime*1000)) }
        catch {
            guard usingGPU else { throw error }
            detector = try EyeCamera.makeDetector(gpu: false); usingGPU = false
            return nil
        }
        let points = result.faceLandmarks.first ?? []
        guard points.count>=478 else { return nil }
        var flat = [Float](repeating: 0,count: points.count*3)
        for (i,p) in points.enumerated() { flat[i*3] = p.x; flat[i*3+1] = p.y; flat[i*3+2] = p.z }
        let array = flat.withUnsafeMutableBufferPointer { raw in BgraPixels.shared.floats(base: raw.baseAddress!,count: Int32(raw.count)) }
        let matrix = result.facialTransformationMatrixes.first
        let transform = KotlinFloatArray(size: matrix == nil ? 0 : 16)
        if let matrix = matrix { for row in 0..<4 { for col in 0..<4 { transform.set(index: Int32(col*4+row),value: matrix.data[row*4+col]) } } }
        let gray = CameraGrayImage(buffer); defer { gray.release() }
        // 这里只提取几何；当前像素的光学测量由相机管线统一计算一次。
        return LandmarkGeometry.shared.face(points: array,matrix: transform,image: gray,optics: false)
    }
    static func point(_ p: Point2f?) -> LabPoint? { p.map { LabPoint(x: Double($0.x),y: Double($0.y)) } }
}
