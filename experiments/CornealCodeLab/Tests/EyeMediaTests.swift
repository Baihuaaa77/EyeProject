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
        let sourceTimes = try decodedTimes(directory.appendingPathComponent("camera.mov"))
        XCTAssertGreaterThanOrEqual(sourceTimes.count,12)
        for file in files {
            let url = directory.appendingPathComponent(file)
            let asset = AVURLAsset(url: url)
            XCTAssertEqual(try XCTUnwrap(asset.tracks(withMediaType: .video).first).naturalSize.width,640)
            let times = try decodedTimes(url)
            XCTAssertEqual(times.count,sourceTimes.count,"\(file) 原片:\(sourceTimes) 派生:\(times)")
            for (a,b) in zip(times,sourceTimes) { XCTAssertEqual(a,b,accuracy: 0.001,file) }
        }
    }
    private func decodedTimes(_ url: URL) throws -> [Double] {
        let asset = AVURLAsset(url: url), reader = try AVAssetReader(asset: asset)
        let track = try XCTUnwrap(asset.tracks(withMediaType: .video).first)
        let output = AVAssetReaderTrackOutput(track: track,outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        reader.add(output); XCTAssertTrue(reader.startReading())
        var times: [Double] = []
        while let sample = output.copyNextSampleBuffer() {
            if CMSampleBufferGetImageBuffer(sample) != nil { times.append(CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))) }
        }
        XCTAssertEqual(reader.status,.completed)
        return times
    }

    func testNativePNGPreservesUncompressedColorPixels() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString,isDirectory: true)
        try FileManager.default.createDirectory(at: directory,withIntermediateDirectories: true)
        var bytes = [UInt8](repeating: 0,count: 32*16*4)
        for y in 0..<16 { for x in 0..<32 {
            let i = (y*32+x)*4; bytes[i] = y<8 ? 7 : 255; bytes[i+1] = 3; bytes[i+2] = y<8 ? 255 : 7; bytes[i+3] = 255
        } }
        try Data(bytes).write(to: directory.appendingPathComponent("temporary_eye_pixels.bgra"))
        let row = FrameRecord(index: 0,pts: 0,hostTime: 10,exposureSeconds: 0.01,iso: 100,width: 32,height: 16,status: "valid",eye: "image_left",roi: LabRect(x: 0,y: 0,width: 32,height: 16),nativeOffset: 0,nativeROI: LabRect(x: 0,y: 0,width: 32,height: 16))
        XCTAssertEqual(try NativeEyeSnapshots.write(directory: directory,records: [row],results: [],start: 10),1)
        let image = try XCTUnwrap(CIImage(contentsOf: directory.appendingPathComponent("image_left_original.png")))
        let top = rgba(image,x: 4,y: 14), bottom = rgba(image,x: 4,y: 2)
        for (actual,expected) in zip(top,[255,3,7,255]) { XCTAssertLessThanOrEqual(abs(actual-expected),1) }
        for (actual,expected) in zip(bottom,[7,3,255,255]) { XCTAssertLessThanOrEqual(abs(actual-expected),1) }
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("temporary_eye_pixels.bgra").path))
    }

    private func rgba(_ image: CIImage,x: Int,y: Int) -> [Int] {
        var bytes = [UInt8](repeating: 0,count: 4)
        bytes.withUnsafeMutableBytes { raw in CIContext().render(image,toBitmap: raw.baseAddress!,rowBytes: 4,bounds: CGRect(x: x,y: y,width: 1,height: 1),format: .RGBA8,colorSpace: CGColorSpaceCreateDeviceRGB()) }
        return bytes.map(Int.init)
    }
}
