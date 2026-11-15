import SwiftUI
import AVFoundation

/// 原生预览直接由相机会话渲染，不经过UIImage编码和SwiftUI逐帧状态更新。
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    final class Surface: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
    func makeUIView(context: Context) -> Surface {
        let view = Surface()
        view.preview.videoGravity = .resizeAspect
        view.preview.session = session
        if let connection = view.preview.connection,connection.isVideoOrientationSupported { connection.videoOrientation = .portrait }
        if let connection = view.preview.connection,connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false; connection.isVideoMirrored = false
        }
        return view
    }
    func updateUIView(_ view: Surface,context: Context) {}
    static func dismantleUIView(_ view: Surface,coordinator: ()) { view.preview.session = nil }
}
