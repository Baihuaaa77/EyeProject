import SwiftUI
import AVKit

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
    @State private var path: [TrialSummary] = []
    var body: some View {
        NavigationStack(path: $path) { setup.navigationDestination(for: TrialSummary.self) { trial in
            TrialResultView(trial: trial,root: model.root,share: { model.share(trial) })
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
        .sheet(isPresented: $model.showingShare) { ShareSheet(urls: model.shareURLs) }
    }
    private var setup: some View {
        Form {
            Section {
                Text("屏幕白点 → 前摄眼区 → 映光定位").font(LabStyle.title)
                Text("验证光学信号，不输出斜视诊断。黑色背景仅显示实验光点；点屏幕任意位置停止。").font(LabStyle.detail)
            }
            Section("1 · 准备相机") {
                if let image = model.preview {
                    Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 220)
                        .accessibilityLabel("未镜像的前置相机预览")
                }
                Text(model.cameraStatus).font(LabStyle.detail)
                Button("准备相机 / 重新检查双眼",action: model.prepare)
            }
            Section("2 · 本轮条件") {
                Picker("照明",selection: $model.configuration.mode) {
                    ForEach(TrialMode.allCases) { mode in Text(mode.title).tag(mode) }
                }
                Picker("固定注视点直径",selection: $model.configuration.dotDiameter) {
                    ForEach([16.0,32,64,96],id: \.self) { Text("\(Int($0)) pt").tag($0) }
                }
                Picker(model.configuration.mode == .motion ? "固定注视点位置" : "白点位置",selection: $model.configuration.dotY) {
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
                HStack { Text("自报距离"); TextField("厘米",value: $model.configuration.distanceCM,format: .number).keyboardType(.decimalPad); Text("cm") }
                Picker("眼镜",selection: $model.configuration.glasses) { ForEach(["未戴镜","框架眼镜","隐形眼镜"],id: \.self) { Text($0) } }
                Picker("环境",selection: $model.configuration.lighting) { ForEach(["室内普通光","暗室","背窗","外置点光源阳性对照"],id: \.self) { Text($0) } }
                Toggle("沿用上一轮锁定曝光",isOn: $model.configuration.reuseExposure)
                Text("对照轮保持白点大小、位置、距离和曝光一致。首次使用或更换环境后关闭沿用，再点准备相机。").font(LabStyle.detail)
            }
            Section("3 · 采集") {
                Text("先比较明暗编码与均值常亮对照。运动验证先用一个白色移动点，再试多个点和彩色点。每轮 32 秒，开始前 2.5 秒恒亮预适应。").font(LabStyle.detail)
                Button("开始 \(model.configuration.mode.title)",action: model.begin).disabled(!model.cameraReady)
                Text("成人先做短时台架验证。编码仅改变小点灰度，2 码元/秒；如感到不适，立即点屏幕停止。实验数据只保存在本机。").font(LabStyle.detail)
            }
            if let error = model.error { Section("本轮提示") { Text(error).foregroundStyle(LabStyle.candidate) } }
            Section("已保存的实验") {
                if model.saved.isEmpty { Text("尚无实验记录").foregroundStyle(LabStyle.secondary) }
                ForEach(model.saved) { trial in
                    NavigationLink(value: trial) {
                        VStack(alignment: .leading) {
                            Text("\(trial.configuration.mode.title) · \(Int(trial.configuration.dotDiameter)) pt")
                            Text(trial.date.formatted(date: .abbreviated,time: .shortened)).font(LabStyle.detail)
                            Text(trial.stoppedEarly ? "提前停止，不计完整对照" : "\(trial.results.filter(\.codedEvidence).count) 眼区通过编码独立验证").font(LabStyle.detail)
                        }
                    }
                }
            }
        }.navigationTitle("光码映光验证")
    }
}

struct TrialResultView: View {
    let trial: TrialSummary
    let root: URL
    let share: () -> Void
    private var directory: URL { root.appendingPathComponent(trial.id) }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading,spacing: 20) {
                Text(trial.configuration.mode.title).font(LabStyle.title)
                Text("\(Int(trial.configuration.dotDiameter)) pt · \(Int(trial.configuration.distanceCM)) cm · \(trial.configuration.glasses) · \(trial.configuration.lighting)").font(LabStyle.detail)
                if trial.stoppedEarly { Text("本轮提前停止，不作为完整条件比较。").foregroundStyle(LabStyle.candidate) }
                ForEach(trial.eligibilityNotes,id: \.self) { Text($0).foregroundStyle(LabStyle.candidate) }
                ForEach(trial.results,id: \.eye) { eye in
                    VStack(alignment: .leading,spacing: 10) {
                        Text(eye.eye == "image_left" ? "画面左眼区（未镜像）" : "画面右眼区（未镜像）").font(LabStyle.heading)
                        if FileManager.default.fileExists(atPath: directory.appendingPathComponent(eye.eye+"_original.png").path) {
                            EyeMediaSection(eye: eye.eye,directory: directory)
                        } else if let image = UIImage(contentsOfFile: directory.appendingPathComponent(eye.eye+".png").path) {
                            Text("分析采样图（旧记录或彩色资料尚未生成）").font(LabStyle.detail)
                            Image(uiImage: image).resizable().interpolation(.none).aspectRatio(2,contentMode: .fit)
                        }
                        Text(eye.message).font(LabStyle.body)
                        Text("有效眼区 \(eye.samples) 帧 · 主 App 基础映光候选率 \(Int(eye.rawCandidateRate*100))%").font(LabStyle.detail)
                        if trial.configuration.mode != .motion {
                        HStack { metric("前半段 r",eye.trainCorrelation); metric("后半段 r",eye.heldOutCorrelation); metric("错码 |r|",eye.wrongCodeCorrelation) }
                        HStack { metric("响应幅度",eye.amplitude); metric("拟合延迟 ms",eye.lagSeconds.map { $0*1000 }) }
                        }
                        if let motion = eye.motionResults {
                            ForEach(0..<trial.configuration.probeCount,id: \.self) { id in
                                if let item = motion.first(where: { $0.sourceID == id }) {
                                    MotionResultView(result: item)
                                } else { Text("移动光点 \(id+1)：未找到覆盖前后半段的稳定轨迹。").font(LabStyle.detail) }
                            }
                        }
                        if eye.heatmap.count == 2048 || eye.heatmap.count == CornealAnalyzer.count {
                            Text("码相关热图（对照轮为伪码）").font(LabStyle.subheading)
                            HeatmapView(values: eye.heatmap).aspectRatio(2,contentMode: .fit)
                            Text("局部高通信号与编码参考").font(LabStyle.subheading)
                            SignalView(signal: eye.traceSignal,code: eye.traceCode).frame(height: 100)
                        }
                    }.padding().background(LabStyle.background).clipShape(RoundedRectangle(cornerRadius: LabStyle.radius))
                }
                Text(trial.algorithmVersion.hasPrefix("corneal-feasibility-3") ? "基础检测：MediaPipe 与主 App 共用 Kotlin 光学算法。编码解调是新增筛选，不改变基础映光阈值。彩色原图来自原视频原生眼区裁剪，放大视频不增加光学分辨率。" : "这是旧版 Vision 实验记录，不能视为主 App 同算法结果。").font(LabStyle.detail)
                Text("叠加图：橙色为虹膜边界，虚线为搜索范围；青色为瞳孔/虹膜参考中心，黄色为主 App 基础映光候选；绿色大圈为通过编码验证的候选，橙色大圈为未通过的候选。仍须人工确认角膜来源。").font(LabStyle.detail)
                LocalMovieView(url: directory.appendingPathComponent("camera.mov"),title: "原始全画面视频").frame(minHeight: 240)
                Button("导出本轮视频、图像与时间日志",action: share).buttonStyle(.borderedProminent)
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
    @State private var annotated = false
    var body: some View {
        VStack(alignment: .leading,spacing: 10) {
            Picker("彩色资料",selection: $annotated) { Text("彩色原始内容").tag(false); Text("检测结果叠加").tag(true) }.pickerStyle(.segmented)
            let suffix = annotated ? "_overlay" : "_original"
            if let image = UIImage(contentsOfFile: directory.appendingPathComponent(eye+suffix+".png").path) {
                Text(annotated ? "彩色原图上的检测结果" : "眼部彩色原图（原生像素裁剪、显示放大）").font(LabStyle.detail)
                Image(uiImage: image).resizable().interpolation(.none).aspectRatio(2,contentMode: .fit)
                Text("图像尺寸 \(Int(image.size.width)) × \(Int(image.size.height))").font(LabStyle.detail)
            }
            let video = directory.appendingPathComponent(eye+suffix+".mov")
            if FileManager.default.fileExists(atPath: video.path) {
                LocalMovieView(url: video,title: annotated ? "放大眼区视频 · 检测叠加" : "放大眼区视频 · 彩色原内容").id(video)
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
    var body: some View { Canvas { context,size in
        for (values,color) in [(signal,LabStyle.primary),(code,LabStyle.secondary)] {
            guard values.count > 1 else { continue }
            let lo = values.min() ?? 0, hi = max(lo+0.001,values.max() ?? 1)
            var path = Path()
            for (i,v) in values.enumerated() {
                let p = CGPoint(x: Double(i)/Double(values.count-1)*size.width,y: size.height-(v-lo)/(hi-lo)*size.height)
                if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
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
    private func number(_ value: Double) -> String { String(format: "%.2f",value) }
    var body: some View {
        VStack(alignment: .leading,spacing: 8) {
            Text("移动光点 \(result.sourceID+1) · \(result.color == "white" ? "白" : result.color == "red" ? "红" : result.color == "green" ? "绿" : "蓝")").font(LabStyle.heading)
            Text(result.message).foregroundStyle(result.accepted ? LabStyle.coded : LabStyle.candidate).font(LabStyle.detail)
            Text("误差下降：前半 \(number(result.trainGain)) / 后半 \(number(result.heldOutGain)) / 错轨迹 \(number(result.wrongTrajectoryGain))").font(LabStyle.detail)
            Text("位移 RMS \(number(result.amplitudePixels)) px · 验证误差 \(number(result.heldOutErrorPixels)) px · 覆盖 \(Int(result.coverage*100))% · 延迟 \(Int(result.lagSeconds*1000)) ms").font(LabStyle.detail)
            Text("实际亮点轨迹（橙） / 固定模型预测（灰）；虚线为验证段起点").font(LabStyle.detail)
            MotionTraceView(observations: result.observations,predicted: result.predicted,axisX: true).frame(height: 90)
            MotionTraceView(observations: result.observations,predicted: result.predicted,axisX: false).frame(height: 90)
        }.padding(.vertical,8)
    }
}

struct MotionTraceView: View {
    let observations: [MotionObservation]
    let predicted: [LabPoint]
    let axisX: Bool
    var body: some View { Canvas { context,size in
        let actual = observations.map { axisX ? $0.point.x : $0.point.y }
        let model = predicted.map { axisX ? $0.x : $0.y }
        let all = actual+model, lo = (all.min() ?? 0)-0.1, hi = (all.max() ?? 1)+0.1
        for (values,color) in [(actual,LabStyle.primary),(model,LabStyle.secondary)] {
            var path = Path()
            for i in 0..<min(values.count,observations.count) {
                let p = CGPoint(x: observations[i].time/32*size.width,y: size.height-(values[i]-lo)/(hi-lo)*size.height)
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
