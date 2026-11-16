// swift-tools-version: 5.9
import PackageDescription

// 与手机编译同一份算法源文件；Linux/macOS 可运行，不包含 UIKit 或相机替身。
let package = Package(name: "CornealAlgorithms",platforms: [.macOS(.v12)],products: [.executable(name: "corneal-replay",targets: ["CornealReplay"])],targets: [
    .target(name: "CornealAlgorithms",path: "Sources",exclude: ["LabApp.swift","LabCamera.swift","LabModel.swift","MainAppDetector.swift","StimulusDisplay.swift","EyeMediaExporter.swift","NativeEyeSnapshots.swift","DeviceOptics.swift","CameraPreview.swift","InteractiveMedia.swift","VideoGlintTracking.swift","GlintTrackingView.swift","FixationPatternDisplay.swift"],sources: ["CornealCore.swift","IrisRegion.swift","MotionDecoder.swift","SpotGeometry.swift","ScreenReflection.swift","CornealReplay.swift","ExperimentPlan.swift","OpticalPrediction.swift","TrialDataExport.swift","GlintTracking.swift","NativeGlintTracking.swift","FixationPattern.swift"]),
    .executableTarget(name: "CornealReplay",dependencies: ["CornealAlgorithms"],path: "Replay"),
    .testTarget(name: "CornealAlgorithmTests",dependencies: ["CornealAlgorithms"],path: "AlgorithmTests")
])
