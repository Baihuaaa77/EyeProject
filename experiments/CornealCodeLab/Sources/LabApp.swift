import SwiftUI
import AVKit
import UniformTypeIdentifiers

@main
struct CornealCodeLabApp: App {
    @StateObject private var model = LabModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            LabRootView(model: model).preferredColorScheme(.dark)
                .onChange(of: scenePhase) { if $0 != .active { model.interrupted() } }
        }
    }
}

struct LabRootView: View {
    @ObservedObject var model: LabModel
    @State private var importingPlan = false
    @State private var path: [TrialSummary] = []
    var body: some View {
        NavigationStack(path: $path) { setup.navigationDestination(for: TrialSummary.self) { trial in
            TrialResultView(trial: trial,model: model)
        } }
        .overlay {
            if model.phase == .warming || model.phase == .recording {
                StimulusSurface(driver: model.driver).ignoresSafeArea()
                    .contentShape(Rectangle()).onTapGesture { model.stop() }
                    .accessibilityLabel("正在采集，双击屏幕停止")
            } else if model.phase == .analyzing {
                VStack(spacing: 16) { ProgressView(); Text("正在解调并生成彩色眼区图像与视频") }
                    .frame(maxWidth: .infinity,maxHeight: .infinity).background(LabStyle.background)
            }
        }
        .onChange(of: model.selected) { if let trial = $0 { path = [trial] } }
        .statusBarHidden(model.phase == .warming || model.phase == .recording)
        .persistentSystemOverlays(model.phase == .warming || model.phase == .recording ? .hidden : .automatic)
        .tint(LabStyle.primary)
        .fileImporter(isPresented: $importingPlan,allowedContentTypes: [.json]) { result in
            switch result { case .success(let url): model.importPlan(url); case .failure(let error): model.error = error.localizedDescription }
        }
        .sheet(isPresented: $model.showingShare) { ShareSheet(urls: model.shareURLs) }
        .alert("提示",isPresented: Binding(get: { model.error != nil },set: { if !$0 { model.error = nil } })) { Button("知道了") { model.error = nil } } message: { Text(model.error ?? "") }
    }
    private var setup: some View {
        Form {
            Section {
                Text("屏幕白点 → 前摄眼区 → 映光定位").font(LabStyle.title)
                Text("验证光学信号，不输出斜视诊断。采集时点屏幕任意位置停止。").font(LabStyle.detail)
            }
            Section("实验清单") {
                Button("导入实验顺序 JSON") { importingPlan = true }
                if let plan = model.experimentPlan {
                    Text("第 \(min(model.planIndex+1,plan.rounds.count))/\(plan.rounds.count) 轮 · 参数已锁定").font(LabStyle.detail)
                    Button("退出清单模式",action: model.leavePlan)
                }
            }
            Section("1 · 准备相机") {
                CameraPreview(session: model.captureSession).frame(height: 220)
                    .accessibilityLabel("未镜像的前置相机实时预览")
                Text(model.cameraStatus).font(LabStyle.detail)
                Button("准备相机 / 重新检查双眼",action: model.prepare)
            }
            Section("2 · 本轮条件") {
                Toggle("优先4K（不支持时记录实际回退）",isOn: $model.configuration.prefer4K)
                Picker("采集帧率",selection: $model.configuration.captureFPS) { Text("30 fps").tag(30); Text("60 fps").tag(60) }
                Picker("手动曝光",selection: $model.configuration.exposureMS) { Text("20 ms").tag(20.0); Text("10 ms").tag(10.0); Text("5 ms").tag(5.0) }
                TextField("ISO",value: $model.configuration.exposureISO,format: .number).keyboardType(.decimalPad)
                Toggle("快速码（100 ms，320码元，32秒）",isOn: Binding(get: { model.configuration.chipSeconds == 0.1 },set: { fast in
                    model.configuration.chipSeconds = fast ? 0.1 : 0.5; model.configuration.chipCount = fast ? 320 : 64
                    model.configuration.transitionGuardMS = fast ? 40 : 0
                }))
                Toggle("红绿差通道编码（需实测色亮度）",isOn: $model.configuration.colorCode)
                Picker("照明",selection: $model.configuration.mode) {
                    ForEach(TrialMode.allCases) { mode in Text(mode.title).tag(mode) }
                }
                Picker("固定注视点直径",selection: $model.configuration.dotDiameter) {
                    ForEach([16.0,32,64,96,128,160],id: \.self) { Text("\(Int($0)) pt").tag($0) }
                }
                Picker("固定注视点位置",selection: $model.configuration.dotY) {
                    Text("上方，靠近前摄").tag(0.15); Text("上四分之一").tag(0.25); Text("屏幕中央").tag(0.50)
                }
                if model.configuration.mode == .motion {
                    Picker("移动光点直径",selection: $model.configuration.probeDiameter) {
                        ForEach([16.0,32,64,96],id: \.self) { Text("\(Int($0)) pt").tag($0) }
                    }
                    Stepper("移动光点 \(model.configuration.probeCount) 个",value: $model.configuration.probeCount,in: 1...3)
                    Toggle("移动光点使用红 / 绿 / 蓝",isOn: $model.configuration.colorfulProbes)
                    Picker("移动范围",selection: $model.configuration.motionScale) {
                        Text("小").tag(0.5); Text("标准").tag(1.0); Text("大").tag(1.3)
                    }
                    Text("始终盯住静止的小白点。其他光点在外围移动，不要追看；固定点与移动点的直径分别由上方设置决定。").font(LabStyle.detail)
                } else if model.configuration.mode.isScreen {
                    Picker("全屏恒亮灰度",selection: $model.configuration.screenLevel) {
                        Text("50%").tag(0.5); Text("70%").tag(0.7); Text("100%").tag(1.0)
                    }
                    Text(model.configuration.mode == .screenTarget ? "全屏恒亮，盯住黑点。先匹配屏幕轮廓，再检查内部暗圆斑；小黑点可能无法在原始像素中分辨。" : "全屏恒亮但不画黑点。保持上一轮视线，检查算法是否把环境暗纹误认成黑点。").font(LabStyle.detail)
                } else if model.configuration.mode == .coded || model.configuration.mode == .steady {
                    Picker("明暗变化",selection: Binding(get: { model.configuration.modulation },set: { value in
                        model.configuration.modulation = value
                        model.configuration.mean = value == 0.15 ? 0.8 : 1-value
                    })) {
                        Text("弱：65% ↔ 95%（旧版）").tag(0.15)
                        Text("中：20% ↔ 100%").tag(0.4)
                        Text("强：5% ↔ 100%").tag(0.475)
                        Text("全幅：0% ↔ 100%").tag(0.5)
                    }
                    Text("均值对照恒亮值：\(Int((model.configuration.mean*100).rounded()))%。百分比为输出通道值，不是实测物理亮度。").font(LabStyle.detail)
                }
                HStack { Text("屏幕亮度"); Slider(value: $model.configuration.screenBrightness,in: 0.3...1); Text("\(Int(model.configuration.screenBrightness*100))%") }
                LabeledContent("自动距离",value: model.measuredDistanceCM.map { String(format: "%.1f cm",$0) } ?? "等待双眼定位")
                Picker("眼镜",selection: $model.configuration.glasses) { ForEach(["未戴镜","框架眼镜","隐形眼镜"],id: \.self) { Text($0) } }
                Picker("环境",selection: $model.configuration.lighting) { ForEach(["室内普通光","暗室","背窗","外置点光源阳性对照"],id: \.self) { Text($0) } }
                Toggle("沿用上一轮锁定曝光",isOn: $model.configuration.reuseExposure)
                Text("对照轮保持白点大小、位置、距离和曝光一致。首次使用或更换环境后关闭沿用，再点准备相机。").font(LabStyle.detail)
            }
            .disabled(model.experimentPlan != nil)
            Section("3 · 采集") {
                Text("先比较明暗编码与均值常亮对照。运动验证先用一个白色移动点，再试多个点和彩色点。每轮 32 秒，开始前 2.5 秒恒亮预适应。").font(LabStyle.detail)
                if let issue = model.configuration.validationError { Text(issue).foregroundStyle(LabStyle.candidate) }
                Button("开始 \(model.configuration.mode.title)",action: model.begin).disabled(!model.cameraReady || model.configuration.validationError != nil)
                Text("成人先做短时台架验证。编码仅改变小点灰度，码元速率由上方设置；如感到不适，立即点屏幕停止。实验数据只保存在本机。").font(LabStyle.detail)
            }
            if let error = model.error { Section("本轮提示") { Text(error).foregroundStyle(LabStyle.candidate) } }
            Section("已保存的实验") {
                if model.saved.isEmpty { Text("尚无实验记录").foregroundStyle(LabStyle.secondary) }
                ForEach(model.saved) { trial in
                    NavigationLink(value: trial) {
                        VStack(alignment: .leading) {
                            Text("\(trial.configuration.mode.title) · \(Int(trial.configuration.dotDiameter)) pt")
                            Text(trial.date.formatted(date: .abbreviated,time: .shortened)).font(LabStyle.detail)
                            Text(trial.stoppedEarly ? "提前停止，不计完整对照" : trial.configuration.mode.isScreen ? "\(trial.results.filter { $0.screenResult?.structuralEvidence == true }.count) 眼区通过空间模板检查" : "\(trial.results.filter(\.codedEvidence).count) 眼区通过编码独立验证").font(LabStyle.detail)
                        }
                    }
                }
            }
        }.navigationTitle("光码映光验证")
    }
}

struct TrialResultView: View {
    let trial: TrialSummary
    @ObservedObject var model: LabModel
    private var directory: URL { model.root.appendingPathComponent(trial.id) }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading,spacing: 20) {
                Text(trial.configuration.mode.title).font(LabStyle.title)
                Text("\(Int(trial.configuration.dotDiameter)) pt · \(trial.configuration.automaticDistance ? "自动" : "记录")距离 \(String(format: "%.1f",trial.configuration.distanceCM)) cm · \(trial.configuration.glasses) · \(trial.configuration.lighting)").font(LabStyle.detail)
                if trial.stoppedEarly { Text("本轮提前停止，不作为完整条件比较。").foregroundStyle(LabStyle.candidate) }
                ForEach(trial.eligibilityNotes,id: \.self) { Text($0).foregroundStyle(LabStyle.candidate) }
                InteractiveMovieView(directory: directory,trial: trial)
                HStack {
                    Button("导出原视频") { model.shareFile(trial,name: "camera.mov") }
                    if FileManager.default.fileExists(atPath: directory.appendingPathComponent("camera_overlay.mov").path) {
                        Button("导出可视化视频") { model.shareFile(trial,name: "camera_overlay.mov") }
                    }
                }.buttonStyle(.bordered).frame(minHeight: 44)
                VStack(alignment: .leading,spacing: 8) {
                    Button("导出全部数据（单个 JSON）") { model.shareData(trial,includePixels: true) }
                    Button("导出轻量数据（不含像素）") { model.shareData(trial,includePixels: false) }
                    if model.exporting { ProgressView("正在合并数据") }
                }.buttonStyle(.bordered).disabled(model.exporting)
                ForEach(trial.results,id: \.eye) { eye in
                    VStack(alignment: .leading,spacing: 10) {
                        Text(eye.eye == "image_left" ? "画面左眼区（未镜像）" : "画面右眼区（未镜像）").font(LabStyle.heading)
                        if FileManager.default.fileExists(atPath: directory.appendingPathComponent(eye.eye+"_original.png").path) {
                            EyeMediaSection(eye: eye.eye,directory: directory,nativeSnapshot: trial.nativeSnapshots == true,shareFile: { model.shareFile(trial,name: $0) })
                        } else if let image = UIImage(contentsOfFile: directory.appendingPathComponent(eye.eye+".png").path) {
                            Text("分析采样图（旧记录或彩色资料尚未生成）").font(LabStyle.detail)
                            Image(uiImage: image).resizable().interpolation(.none).aspectRatio(2,contentMode: .fit)
                        }
                        Text(eye.message).font(LabStyle.body)
                        if let prediction = eye.opticalPrediction { OpticalEvidenceView(prediction: prediction) }
                        Text("有效眼区 \(eye.samples) 帧 · 基础亮点候选率 \(Int(eye.rawCandidateRate*100))%").font(LabStyle.detail)
                        if let coverage = eye.validFrameCoverage { Text("有效眼区占已分析帧 \(Int(coverage*100))%").font(LabStyle.detail) }
                        if let screen = eye.screenResult {
                            Text("屏幕轮廓覆盖 \(Int(screen.rectangleCoverage*100))% · 暗点覆盖 \(Int(screen.targetCoverage*100))%").font(LabStyle.detail)
                            Text("暗点前半 \(Int(screen.trainTargetCoverage*100))% / 后半 \(Int(screen.heldOutTargetCoverage*100))% · 相对抖动 \(screen.jitterPixels.map { String(format: "%.2f px",$0) } ?? "—")").font(LabStyle.detail)
                            Text("这是反射图像中的注视目标候选，不代表真实注视方向；空间匹配不计为编码成功。").font(LabStyle.detail)
                        }
                        if let rank = eye.shiftRankFraction { Text("全部63个错位参考的经验秩比例 \(String(format: "%.3f",rank))（探索性，非校准p值）").font(LabStyle.detail) }
                        if let shape = eye.shape {
                            Text("圆形度 \(String(format: "%.2f",shape.circularity)) · 短长轴比 \(String(format: "%.2f",shape.axisRatio)) · 原生直径 \(String(format: "%.1f",shape.nativeDiameter)) px").font(LabStyle.detail)
                            Text("验证段形状覆盖 \(Int((eye.shapeCoverage ?? 0)*100))% · \(eye.heldOutShape?.reason ?? "验证段无合格形状")").font(LabStyle.detail)
                        }
                        if trial.configuration.mode != .motion && !trial.configuration.mode.isScreen {
                        HStack { metric("前半段 r",eye.trainCorrelation); metric("后半段 r",eye.heldOutCorrelation); metric("错码 |r|",eye.wrongCodeCorrelation) }
                        HStack { metric("响应幅度",eye.amplitude); metric("拟合延迟 ms",eye.lagSeconds.map { $0*1000 }) }
                        }
                        if let motion = eye.motionResults {
                            ForEach(0..<trial.configuration.probeCount,id: \.self) { id in
                                if let item = motion.first(where: { $0.sourceID == id }) {
                                    MotionResultView(result: item,duration: trial.configuration.duration)
                                } else { Text("移动光点 \(id+1)：未找到覆盖前后半段的稳定轨迹。").font(LabStyle.detail) }
                            }
                        }
                        if eye.heatmap.count == 2048 || eye.heatmap.count == CornealAnalyzer.count {
                            Text("码相关热图（对照轮为伪码）").font(LabStyle.subheading)
                            HeatmapView(values: eye.heatmap).aspectRatio(2,contentMode: .fit)
                            Text("局部高通信号与编码参考（分别归一化）").font(LabStyle.subheading)
                            SignalView(signal: eye.traceSignal,code: eye.traceCode,time: eye.traceTime,duration: trial.configuration.duration).frame(height: 100)
                        }
                    }.padding().background(LabStyle.background).clipShape(RoundedRectangle(cornerRadius: LabStyle.radius))
                }
                Text("新实验使用完整虹膜搜索与共享光学算法；旧记录保留当时范围。放大图片和视频不增加原始光学分辨率。").font(LabStyle.detail)
                Text("叠加图：橙色为虹膜边界，虚线为搜索范围；青色为瞳孔/虹膜参考中心，黄色为主 App 基础映光候选；绿色大圈为通过编码验证的候选，橙色大圈为未通过的候选。仍须人工确认角膜来源。").font(LabStyle.detail)
                DisclosureGroup("参数与判定边界") {
                    Text("运动轮：仅前半段选择轨迹、空间映射与延迟，固定参数验证后半段。增益为相对仅含眼动/头动模型的误差下降比例，要求前半段≥0.45、后半段≥0.30、比错轨迹高0.20、位移≥0.18采样像素、覆盖≥60%，并排除严重运动混杂。明暗轮：只用前半段选择位置与延迟，固定后在后半段验证。参考门槛：前半段 r≥0.35、后半段 r≥0.30、后半段比错码至少高 0.15，局部响应≥0.5 灰度级。这些是工程门槛，不是经临床验证的阈值。延迟不是纯硬件延迟。").font(LabStyle.detail)
                    Text((try? String(contentsOf: directory.appendingPathComponent("camera.json"),encoding: .utf8)) ?? "无相机参数").font(LabStyle.code)
                }
            }.padding()
        }.navigationTitle("本轮验证").navigationBarTitleDisplayMode(.inline)
    }
    private func metric(_ title: String,_ value: Double?) -> some View {
        VStack(alignment: .leading) { Text(title).font(LabStyle.detail); Text(value.map { String(format: "%.2f",$0) } ?? "—").monospacedDigit() }.frame(maxWidth: .infinity,alignment: .leading)
    }
}

struct LocalMovieView: View {
    let title: String
    @State private var player: AVPlayer
    init(url: URL,title: String) { self.title = title; _player = State(initialValue: AVPlayer(url: url)) }
    var body: some View {
        VStack(alignment: .leading) { Text(title).font(LabStyle.subheading); VideoPlayer(player: player).frame(height: 200) }
            .onDisappear { player.pause() }
    }
}

struct EyeMediaSection: View {
    let eye: String
    let directory: URL
    let nativeSnapshot: Bool
    let shareFile: (String) -> Void
    @State private var annotated = false
    var body: some View {
        VStack(alignment: .leading,spacing: 10) {
            Picker("彩色资料",selection: $annotated) { Text("彩色原始内容").tag(false); Text("检测结果叠加").tag(true) }.pickerStyle(.segmented)
            let suffix = annotated ? "_overlay" : "_original"
            if let image = UIImage(contentsOfFile: directory.appendingPathComponent(eye+suffix+".png").path) {
                Text(annotated ? "彩色原图上的检测结果" : (nativeSnapshot ? "眼部彩色原图（相机原生像素、无损PNG）" : "原视频彩色帧（眼区裁剪）")).font(LabStyle.detail)
                EyePhotoView(image: image)
                Text("图像尺寸 \(Int(image.size.width)) × \(Int(image.size.height))").font(LabStyle.detail)
            }
            let enlarged = eye+"_enlarged.png"
            Button("导出放大眼部图片") { shareFile(FileManager.default.fileExists(atPath: directory.appendingPathComponent(enlarged).path) ? enlarged : eye+"_original.png") }.buttonStyle(.bordered).frame(minHeight: 44)
            Button("导出眼部可视化图片") { shareFile(eye+"_overlay.png") }.buttonStyle(.bordered).frame(minHeight: 44)
            DisclosureGroup("眼区视频导出") {
                Button("导出眼区原内容视频") { shareFile(eye+"_original.mov") }.frame(minHeight: 44)
                Button("导出眼区可视化视频") { shareFile(eye+"_overlay.mov") }.frame(minHeight: 44)
            }
        }
    }
}

struct OpticalEvidenceView: View {
    let prediction: OpticalPrediction
    private func number(_ x: Double) -> String { String(format: "%.2f",x) }
    var body: some View {
        VStack(alignment: .leading,spacing: 6) {
            Text("光学尺寸核查").font(LabStyle.subheading)
            Text("自动距离 \(number(prediction.distanceCM)) cm · 光点直径约 \(number(prediction.stimulusDiameterMM)) mm").font(LabStyle.detail)
            Text("预测反射直径 \(number(prediction.expectedDiameterPixels)) px（\(number(prediction.expectedMinPixels))–\(number(prediction.expectedMaxPixels))）").font(LabStyle.body)
            if let observed = prediction.observedDiameterPixels {
                Text("候选等效直径 \(number(observed)) px · 预测的 \(number(prediction.observedToExpected ?? 0)) 倍").font(LabStyle.detail)
            }
            DisclosureGroup("模型假设") {
                Text("屏幕约 \(number(prediction.screenWidthMM)) × \(number(prediction.screenHeightMM)) mm；反射屏幕约 \(number(prediction.reflectedScreenWidthPixels)) × \(number(prediction.reflectedScreenHeightPixels)) px。角膜曲率半径假设 \(number(prediction.cornealRadiusMM)) mm。").font(LabStyle.detail)
                Text(prediction.screenSizeSource).font(LabStyle.detail)
                Text(prediction.distanceSource).font(LabStyle.detail)
                Text(prediction.note).font(LabStyle.detail)
            }
        }
    }
}

struct HeatmapView: View {
    let values: [Double]
    var body: some View { Canvas { context,size in
        let peak = max(values.max() ?? 0,0.001)
        let width = values.count == 2048 ? 64 : CornealAnalyzer.width, height = width/2
        for y in 0..<height { for x in 0..<width {
            let v = max(0,min(1,values[y*width+x]/peak))
            context.fill(Path(CGRect(x: Double(x)*size.width/Double(width),y: Double(y)*size.height/Double(height),width: size.width/Double(width)+0.5,height: size.height/Double(height)+0.5)),with: .color(LabStyle.primary.opacity(v)))
        } }
    }.background(LabStyle.background) }
}

struct SignalView: View {
    let signal: [Double]
    let code: [Double]
    var time: [Double] = []
    var duration: Double = 32
    var body: some View { Canvas { context,size in
        for (values,color) in [(signal,LabStyle.primary),(code,LabStyle.secondary)] {
            guard values.count > 1 else { continue }
            let lo = values.min() ?? 0, hi = max(lo+0.001,values.max() ?? 1)
            var path = Path()
            for (i,v) in values.enumerated() {
                let fraction = time.count == values.count ? time[i]/max(duration,0.001) : Double(i)/Double(values.count-1)
                let p = CGPoint(x: fraction*size.width,y: size.height-(v-lo)/(hi-lo)*size.height)
                if i == 0 || (time.count == values.count && time[i]-time[i-1]>0.4) { path.move(to: p) } else { path.addLine(to: p) }
            }
            context.stroke(path,with: .color(color),lineWidth: 1)
        }
    } }
}

struct IrisOverlay: View {
    let region: IrisRegion
    var body: some View { Canvas { context,size in
        var eyelid = Path()
        for (i,p) in region.eyelid.enumerated() {
            let q = CGPoint(x: p.x*size.width,y: p.y*size.height)
            if i == 0 { eyelid.move(to: q) } else { eyelid.addLine(to: q) }
        }
        eyelid.closeSubpath(); context.clip(to: eyelid)
        let ellipse = Path(ellipseIn: CGRect(x: (region.center.x-region.radiusX)*size.width,y: (region.center.y-region.radiusY)*size.height,width: region.radiusX*2*size.width,height: region.radiusY*2*size.height))
        context.fill(ellipse,with: .color(LabStyle.primary.opacity(0.10)))
        context.stroke(ellipse,with: .color(LabStyle.primary),style: StrokeStyle(lineWidth: 1,dash: [4,3]))
    }.allowsHitTesting(false) }
}

struct MotionResultView: View {
    let result: MotionAnalysis
    var duration: Double = 32
    private func number(_ value: Double) -> String { String(format: "%.2f",value) }
    var body: some View {
        VStack(alignment: .leading,spacing: 8) {
            Text("移动光点 \(result.sourceID+1) · \(result.color == "white" ? "白" : result.color == "red" ? "红" : result.color == "green" ? "绿" : "蓝")").font(LabStyle.heading)
            Text(result.message).foregroundStyle(result.accepted ? LabStyle.coded : LabStyle.candidate).font(LabStyle.detail)
            Text("误差下降：前半 \(number(result.trainGain)) / 后半 \(number(result.heldOutGain)) / 错轨迹 \(number(result.wrongTrajectoryGain))").font(LabStyle.detail)
            Text("位移 RMS \(number(result.amplitudePixels)) px · 验证误差 \(number(result.heldOutErrorPixels)) px · 覆盖 \(Int(result.coverage*100))% · 延迟 \(Int(result.lagSeconds*1000)) ms").font(LabStyle.detail)
            Text("实际亮点轨迹（橙） / 固定模型预测（灰）；虚线为验证段起点").font(LabStyle.detail)
            MotionTraceView(observations: result.observations,predicted: result.predicted,axisX: true,duration: duration).frame(height: 90)
            MotionTraceView(observations: result.observations,predicted: result.predicted,axisX: false,duration: duration).frame(height: 90)
        }.padding(.vertical,8)
    }
}

struct MotionTraceView: View {
    let observations: [MotionObservation]
    let predicted: [LabPoint]
    let axisX: Bool
    var duration: Double = 32
    var body: some View { Canvas { context,size in
        let actual = observations.map { axisX ? $0.point.x : $0.point.y }
        let model = predicted.map { axisX ? $0.x : $0.y }
        let all = actual+model, lo = (all.min() ?? 0)-0.1, hi = (all.max() ?? 1)+0.1
        for (values,color) in [(actual,LabStyle.primary),(model,LabStyle.secondary)] {
            var path = Path()
            for i in 0..<min(values.count,observations.count) {
                let p = CGPoint(x: observations[i].time/max(duration,0.001)*size.width,y: size.height-(values[i]-lo)/(hi-lo)*size.height)
                if i == 0 || observations[i].time-observations[i-1].time>0.4 { path.move(to: p) } else { path.addLine(to: p) }
            }
            context.stroke(path,with: .color(color),lineWidth: 1.5)
        }
        var split = Path(); split.move(to: CGPoint(x: size.width/2,y: 0)); split.addLine(to: CGPoint(x: size.width/2,y: size.height))
        context.stroke(split,with: .color(LabStyle.secondary),style: StrokeStyle(lineWidth: 1,dash: [3,3]))
        context.draw(Text(axisX ? "X" : "Y").font(LabStyle.detail),at: CGPoint(x: 8,y: 8))
    } }
}

struct ShareSheet: UIViewControllerRepresentable {
    let urls: [URL]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: urls,applicationActivities: nil) }
    func updateUIViewController(_ view: UIActivityViewController,context: Context) {}
}

extension TrialSummary: Hashable {
    static func ==(lhs: TrialSummary,rhs: TrialSummary) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}
