import XCTest
import AVFoundation
import CoreImage
import UIKit
@testable import CornealCodeLab

final class EyeMediaTests: XCTestCase {
    func testColorCropsAndAnnotatedMoviesKeepFramesAndOrientation() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString,isDirectory: true)
        try FileManager.default.createDirectory(at: directory,withIntermediateDirectories: true)
        let writer = try AVAssetWriter(outputURL: directory.appendingPathComponent("camera.mov"),fileType: .mov)
        let input = AVAssetWriterInput(mediaType: .video,outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264,AVVideoWidthKey: 64,AVVideoHeightKey: 32])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input,sourcePixelBufferAttributes: nil)
        writer.add(input); XCTAssertTrue(writer.startWriting()); writer.startSession(atSourceTime: .zero)
        var records: [FrameRecord] = []
        for i in 0..<12 {
            let deadline = Date().addingTimeInterval(5)
            while !input.isReadyForMoreMediaData,Date()<deadline { Thread.sleep(forTimeInterval: 0.001) }
            var pixel: CVPixelBuffer?
            XCTAssertEqual(CVPixelBufferCreate(kCFAllocatorDefault,64,32,kCVPixelFormatType_32BGRA,nil,&pixel),kCVReturnSuccess)
            let buffer = try XCTUnwrap(pixel)
            CVPixelBufferLockBaseAddress(buffer,[])
            let bytes = try XCTUnwrap(CVPixelBufferGetBaseAddress(buffer)).assumingMemoryBound(to: UInt8.self)
            let stride = CVPixelBufferGetBytesPerRow(buffer)
            for y in 0..<32 { for x in 0..<64 {
                let offset = y*stride+x*4
                bytes[offset] = y<16 ? 20 : 220; bytes[offset+1] = 30; bytes[offset+2] = y<16 ? 220 : 20; bytes[offset+3] = 255
            } }
            CVPixelBufferUnlockBaseAddress(buffer,[])
            XCTAssertTrue(adaptor.append(buffer,withPresentationTime: CMTime(value: Int64(i),timescale: 10)))
            for eye in ["image_left","image_right"] {
                let iris = IrisRegion(center: LabPoint(x: 0.5,y: 0.5),radiusX: 0.2,radiusY: 0.3,confidence: 1,eyelid: [LabPoint(x: 0,y: 0),LabPoint(x: 1,y: 0),LabPoint(x: 1,y: 1),LabPoint(x: 0,y: 1)])
                records.append(FrameRecord(index: i,pts: 900+Double(i)/10,hostTime: 500+Double(i)/10,exposureSeconds: 0.01,iso: 100,width: 64,height: 32,status: "valid",eye: eye,
                    roi: LabRect(x: 16,y: 4,width: 32,height: 16),pupil: LabPoint(x: 32,y: 12),rawCandidate: LabPoint(x: 0.55,y: 0.5),saturation: 0,patchOffset: nil,iris: iris,baselinePupil: LabPoint(x: 0.5,y: 0.5)))
            }
        }
        input.markAsFinished()
        let finished = expectation(description: "原始合成视频写入")
        writer.finishWriting { finished.fulfill() }; wait(for: [finished],timeout: 10)
        XCTAssertEqual(writer.status,.completed)
        let files = try EyeMediaExporter.export(directory: directory,records: records,results: [],start: 500,videoStartPTS: 900)
        XCTAssertEqual(files.count,4)
        let image = try XCTUnwrap(CIImage(contentsOf: directory.appendingPathComponent("image_left_original.png")))
        XCTAssertEqual(image.extent.width,32); XCTAssertEqual(image.extent.height,16)
        let top = rgba(image,x: 4,y: 14), bottom = rgba(image,x: 4,y: 2)
        XCTAssertGreaterThan(top[0],top[2]*3); XCTAssertGreaterThan(bottom[2],bottom[0]*3)
        let annotation = try XCTUnwrap(UIImage(contentsOfFile: directory.appendingPathComponent("image_left_overlay.png").path))
        XCTAssertEqual(annotation.size.width,640); XCTAssertEqual(annotation.size.height,320)
        let attachment = XCTAttachment(image: annotation); attachment.name = "合成彩色眼区-检测叠加"; attachment.lifetime = .keepAlways; add(attachment)
        for file in files {
            let asset = AVURLAsset(url: directory.appendingPathComponent(file))
            let track = try XCTUnwrap(asset.tracks(withMediaType: .video).first)
            XCTAssertEqual(track.naturalSize.width,640)
            let reader = try AVAssetReader(asset: asset)
            let output = AVAssetReaderTrackOutput(track: track,outputSettings: nil); reader.add(output); XCTAssertTrue(reader.startReading())
            var count = 0; while output.copyNextSampleBuffer() != nil { count += 1 }
            XCTAssertEqual(count,12,file)
        }
    }
    private func rgba(_ image: CIImage,x: Int,y: Int) -> [Int] {
        var bytes = [UInt8](repeating: 0,count: 4)
        bytes.withUnsafeMutableBytes { raw in CIContext().render(image,toBitmap: raw.baseAddress!,rowBytes: 4,bounds: CGRect(x: x,y: y,width: 1,height: 1),format: .RGBA8,colorSpace: CGColorSpaceCreateDeviceRGB()) }
        return bytes.map(Int.init)
    }
}
