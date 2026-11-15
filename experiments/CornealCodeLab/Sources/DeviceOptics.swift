import UIKit
import AVFoundation
import EyeScreening

enum DeviceOptics {
    /// UIKit 不公开屏幕毫米尺寸。仅对已核对的原生分辨率使用像素密度，其余明确记录估算。
    static func screen(_ screen: UIScreen) -> (Double,Double,String) {
        let w = Int(min(screen.nativeBounds.width,screen.nativeBounds.height))
        let h = Int(max(screen.nativeBounds.width,screen.nativeBounds.height))
        let density: Double
        let known: Bool
        switch (w,h) {
        case (750,1334),(828,1792): density = 326; known = true
        case (1080,1920): density = 401; known = true
        case (1125,2436),(1242,2688): density = 458; known = true
        case (1080,2340): density = 476; known = true
        case (1170,2532),(1179,2556),(1284,2778),(1290,2796),(1206,2622),(1320,2868): density = 460; known = true
        default: density = 460; known = false
        }
        return (Double(w)/density*25.4,Double(h)/density*25.4,
            "原生屏幕\(w)×\(h)px，\(known ? "分辨率规格映射" : "未知机型假设")\(Int(density))ppi，矩形包络估计")
    }

    /// 与主 App 相同：优先帧内参，缺失时用当前格式视场角；距离调用共用 DepthFromIris。
    static func focal(_ sample: CMSampleBuffer,device: AVCaptureDevice,width: Int,height: Int) -> (Double,String) {
        if let data = CMGetAttachment(sample,key: kCMSampleBufferAttachmentKey_CameraIntrinsicMatrix,attachmentModeOut: nil) as? Data,data.count>=48 {
            let values = data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
            let f = Double(max(values[0],values[5]))
            if f.isFinite && f>0 { return (f,"相机帧内参＋共享虹膜测距") }
        }
        let angle = Double(device.activeFormat.videoFieldOfView)*Double.pi/180
        return (Double(max(width,height))/(2*tan(angle/2)),"相机视场角回退＋共享虹膜测距")
    }
}
