import Foundation
import CornealAlgorithms

do {
    if CommandLine.arguments.count == 5,CommandLine.arguments[1] == "--track-native" {
        try GlintReplay.run(trial: URL(fileURLWithPath: CommandLine.arguments[2]),nativeDirectory: URL(fileURLWithPath: CommandLine.arguments[3]),output: URL(fileURLWithPath: CommandLine.arguments[4]))
        print("已使用手机同源算法在原生眼区逐帧复算。")
        exit(0)
    }
    guard CommandLine.arguments.count == 3 else {
        throw NSError(domain: "复算",code: 1,userInfo: [NSLocalizedDescriptionKey: "用法：corneal-replay 实验目录 新结果.json"])
    }
    try CornealReplay.run(directory: URL(fileURLWithPath: CommandLine.arguments[1]),output: URL(fileURLWithPath: CommandLine.arguments[2]))
    print("已从原始眼区与显示日志重新运行当前版本检测，原始 summary.json 保留。")
} catch {
    // 解码失败时保留字段路径，避免只显示“格式错误”而无法找到损坏记录。
    FileHandle.standardError.write(Data(("复算失败：\(error.localizedDescription)\n\(String(describing: error))\n").utf8))
    exit(1)
}
