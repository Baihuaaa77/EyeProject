import AVFoundation
import UIKit
import EyeScreening
import MediaPipeTasksVision

/// 直接调用主 App 的 SDK 配置、像素适配器、478 点几何和 Kotlin 映光算法。
final class MainAppDetector {
    private var detector: FaceLandmarker
    private(set) var usingGPU = true
    init() throws {
        do { detector = try EyeCamera.makeDetector(gpu: true) }
        catch { detector = try EyeCamera.makeDetector(gpu: false); usingGPU = false }
    }
    func analyze(_ buffer: CVPixelBuffer, hostTime: Double) throws -> FaceMetrics? {
        let image = try MPImage(pixelBuffer: buffer,orientation: .up)
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
        return LandmarkGeometry.shared.face(points: array,matrix: transform,image: gray,optics: true)
    }
    static func point(_ p: Point2f?) -> LabPoint? { p.map { LabPoint(x: Double($0.x),y: Double($0.y)) } }
}
