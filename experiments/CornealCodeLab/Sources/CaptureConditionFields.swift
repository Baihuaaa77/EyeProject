import SwiftUI
import UIKit

struct CaptureConditionFields: View {
    @ObservedObject var model: LabModel
    @FocusState private var editingISO: Bool
    private func dismiss() { editingISO = false; LabKeyboard.dismiss() }
    var body: some View {
        Group {
            Toggle("优先4K（记录实际回退）",isOn: $model.configuration.prefer4K)
            Picker("采集帧率",selection: $model.configuration.captureFPS) { Text("30 fps").tag(30); Text("60 fps").tag(60) }
            Picker("手动曝光",selection: $model.configuration.exposureMS) { Text("20 ms").tag(20.0); Text("10 ms").tag(10.0); Text("5 ms").tag(5.0) }
            HStack {
                Text("ISO")
                TextField("ISO",value: $model.configuration.exposureISO,format: .number).keyboardType(.decimalPad).focused($editingISO)
                    .multilineTextAlignment(.trailing).frame(minHeight: 44)
            }
            Picker("照明",selection: Binding(get: { model.configuration.mode },set: { model.configuration.selectMode($0); dismiss() })) {
                ForEach(TrialMode.available) { mode in Text(mode.title).tag(mode) }
            }
            if model.configuration.mode != .dark { coding }
            Picker("白点直径",selection: $model.configuration.dotDiameter) {
                ForEach([16.0,32,64,96,128,160],id: \.self) { Text("\(Int($0)) pt").tag($0) }
            }
            Picker("白点位置",selection: $model.configuration.dotPlacement) {
                ForEach(DotPlacement.available) { position in Text(position.title).tag(position) }
                if model.configuration.dotPlacement == .legacyFraction { Text("历史相对位置").tag(DotPlacement.legacyFraction) }
            }
            SpotBudgetView(diameter: model.configuration.dotDiameter,distance: model.measuredDistanceCM,irisPixels: model.measuredIrisPixels)
            BrightnessControlView(controller: model.brightness,requested: $model.configuration.screenBrightness,set: model.setBrightness)
            LabeledContent("自动距离",value: model.measuredDistanceCM.map { String(format: "%.1f cm",$0) } ?? "等待双眼定位")
            Picker("眼镜",selection: $model.configuration.glasses) { ForEach(["未戴镜","框架眼镜","隐形眼镜"],id: \.self) { Text($0) } }
            Picker("环境",selection: $model.configuration.lighting) { ForEach(["室内普通光","暗室","背窗","外置点光源阳性对照"],id: \.self) { Text($0) } }
            Toggle("沿用上一轮锁定曝光",isOn: $model.configuration.reuseExposure)
        }
        .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("收起键盘",action: dismiss) } }
        .onChange(of: model.phase) { if $0 != .setup { dismiss() } }
    }
    private var coding: some View {
        Group {
            Picker("闪烁编码",selection: Binding(get: { model.configuration.codingScheme },set: {
                model.configuration.codingScheme = $0; model.configuration.selectMode(model.configuration.mode)
            })) {
                ForEach(CodingScheme.allCases) { scheme in Text(scheme.title).tag(scheme) }
            }
            Picker("码元时长",selection: Binding(get: { model.configuration.chipSeconds },set: {
                model.configuration.chipSeconds = $0; model.configuration.chipCount = Int((32/$0).rounded())
            })) {
                Text("500 ms").tag(0.5); Text("250 ms").tag(0.25); Text("125 ms").tag(0.125)
                if ![0.5,0.25,0.125].contains(model.configuration.chipSeconds) { Text("历史时长").tag(model.configuration.chipSeconds) }
            }
            Text(model.configuration.codingScheme.detail).font(LabStyle.detail)
            if model.configuration.mode == .coded {
                Text("新实验默认0%灭灯与100%亮灯；切换和曝光混合帧单列。").font(LabStyle.detail)
            } else if model.configuration.mode == .steady {
                Picker("常亮强度",selection: $model.configuration.mean) {
                    Text("匹配编码时间均值").tag(model.configuration.codingScheme.meanReference)
                    Text("100%").tag(1.0)
                }
            }
        }
    }
}

struct SpotBudgetView: View {
    let diameter: Double
    let distance: Double?
    let irisPixels: Double?
    var body: some View {
        if let distance = distance,let iris = irisPixels,
           let pixels = OpticalPrediction.diameter(distanceCM: distance,irisPixels: iris,sourceMM: DeviceOptics.screen(UIScreen.main).0*diameter/Double(UIScreen.main.bounds.width)) {
            Text("预计映光直径约 \(String(format: "%.1f",pixels)) 个原生像素\(pixels<2 ? "，细节可能难以分辨" : "")").font(LabStyle.detail)
        }
    }
}
