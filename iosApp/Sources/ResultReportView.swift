import SwiftUI

/// 结果页：内容来自共用核心的 ResultReport（快照里的 "report"），与安卓 ReportComponents 同一排版：
/// 结论卡（小瞳 + 等级 + 一句结论 + 一句建议）→ 关键指标 2×2 → 判断依据（点开看明细）→ 操作 →
/// 「查看本次数据」「导出数据」→ 一行说明。首屏不放成段小字，明细都在数据页里。
struct ResultReportView: View {
    @ObservedObject var runtime: EyeRuntime
    @State private var dataOpen = false

    var body: some View {
        if let report = ReportModel(runtime.state["report"] as? [String: Any]) {
            VStack(spacing: 14) {
                ResultHero(report: report)
                // 数据无效时原因已在 ProblemCard 列出，这里只为有效结果说明可靠性
                if report.valid && !report.qualityNotes.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("数据可靠性说明").font(EyeStyle.subtitle)
                        Text(report.qualityNotes[0]).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                        if report.qualityNotes.count > 1 {
                            DisclosureGroup("查看说明") {
                                ForEach(Array(report.qualityNotes.dropFirst().enumerated()), id: \.offset) { _, note in
                                    Text(note).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                                }
                            }
                        }
                    }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
                        .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radius))
                }
                if !report.valid {
                    ProblemCard(problems: report.problems)
                } else {
                    MetricGrid(metrics: report.metrics)
                    if !report.evidence.isEmpty { EvidenceCard(evidence: report.evidence) }
                }
                // 解读放在规则结论与依据之后：先看规则判了什么，再看通俗解释
                if !runtime.string("savedFile").isEmpty { AiExplanationView(fileName: runtime.string("savedFile")) }
                actions(report)
                HStack(spacing: 10) {
                    Button("查看本次数据") { dataOpen = true }.buttonStyle(EyeButtonStyle(role: .secondary))
                    if let file = exportURL {
                        ShareLink(item: file) { Text("导出数据") }.buttonStyle(EyeButtonStyle(role: .secondary))
                    }
                }
                Text(report.footnote).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .sheet(isPresented: $dataOpen) { ReportDataSheet(report: report, exportURL: exportURL) }
        }
    }

    /// 已保存的完整记录（问卷、逐题作答、逐帧数据、特征与判定依据）；经系统分享面板导出，不经网络上传。
    private var exportURL: URL? {
        let name = runtime.string("savedFile")
        return name.isEmpty ? nil : EyeRuntime.recordsDirectory.appendingPathComponent(name)
    }

    @ViewBuilder private func actions(_ report: ReportModel) -> some View {
        // 与安卓一致：干眼在不能延长时以重测为主操作；老视/斜视在结果不确定或无效时以重测为主操作
        let retestFirst = runtime.kind == "DRY_EYE" || report.level == "UNCERTAIN" || !report.valid
        if runtime.bool("canExtend") { Button("延长测试（3 分钟）") { runtime.act("extended") }.buttonStyle(EyeButtonStyle()) }
        if runtime.bool("canRest") { Button("闭眼休息 1 分钟后再测") { runtime.act("rest") }.buttonStyle(EyeButtonStyle(role: .secondary)) }
        Button(runtime.kind == "DRY_EYE" ? "重新检测" : "重新测试") { runtime.act("restart") }
            .buttonStyle(EyeButtonStyle(role: retestFirst && !runtime.bool("canExtend") ? .primary : .secondary))
    }
}

// MARK: - 数据模型（解析快照）

struct ReportModel {
    struct Metric: Identifiable { let id: Int; let label: String; let value: String; let status: String }
    struct Evidence: Identifiable { let id: String; let title: String; let status: String; let summary: String; let details: [String] }
    struct Row: Identifiable { let id: Int; let label: String; let value: String }
    struct Section: Identifiable { let id: Int; let title: String; let rows: [Row] }

    let level: String, badge: String, title: String, advice: String, mood: String, footnote: String, version: String
    let valid: Bool
    let qualityNotes: [String]
    let metrics: [Metric], evidence: [Evidence], problems: [String], sections: [Section]

    init?(_ dict: [String: Any]?) {
        guard let d = dict, let level = d["level"] as? String else { return nil }
        self.level = level
        badge = d["badge"] as? String ?? ""; title = d["title"] as? String ?? ""
        advice = d["advice"] as? String ?? ""; mood = d["mood"] as? String ?? "HAPPY"
        footnote = d["footnote"] as? String ?? ""; version = d["version"] as? String ?? ""
        valid = d["valid"] as? Bool ?? false
        qualityNotes = d["qualityNotes"] as? [String] ?? []
        metrics = (d["metrics"] as? [[String: Any]] ?? []).enumerated().map { i, m in
            Metric(id: i, label: m["label"] as? String ?? "", value: m["value"] as? String ?? "—", status: m["status"] as? String ?? "UNAVAILABLE")
        }
        evidence = (d["evidence"] as? [[String: Any]] ?? []).map { e in
            Evidence(id: e["key"] as? String ?? UUID().uuidString, title: e["title"] as? String ?? "", status: e["status"] as? String ?? "UNAVAILABLE",
                summary: e["summary"] as? String ?? "", details: e["details"] as? [String] ?? [])
        }
        problems = d["problems"] as? [String] ?? []
        sections = (d["sections"] as? [[String: Any]] ?? []).enumerated().map { i, s in
            Section(id: i, title: s["title"] as? String ?? "", rows: (s["rows"] as? [[String: Any]] ?? []).enumerated().map { j, r in
                Row(id: j, label: r["label"] as? String ?? "", value: r["value"] as? String ?? "")
            })
        }
    }
}

/// 等级与证据状态的配色，与安卓 toneFor 一致。
enum ReportTone {
    static func level(_ value: String) -> (Color, Color) {
        switch value {
        case "LOW": return (EyeStyle.success, EyeStyle.successSoft)
        case "MEDIUM": return (EyeStyle.warning, EyeStyle.warningSoft)
        case "HIGH": return (EyeStyle.danger, EyeStyle.dangerSoft)
        case "UNCERTAIN": return (EyeStyle.primary, EyeStyle.primarySoft)
        default: return (EyeStyle.secondary, EyeStyle.neutralSoft)
        }
    }
    static func status(_ value: String) -> (Color, Color) {
        switch value {
        case "NORMAL": return (EyeStyle.success, EyeStyle.successSoft)
        case "ABNORMAL": return (EyeStyle.warning, EyeStyle.warningSoft)
        case "UNCERTAIN": return (EyeStyle.primary, EyeStyle.primarySoft)
        default: return (EyeStyle.secondary, EyeStyle.neutralSoft)
        }
    }
    static func label(_ value: String) -> String {
        switch value {
        case "NORMAL": return "正常"; case "ABNORMAL": return "异常"; case "UNCERTAIN": return "不确定"; default: return "无法评估"
        }
    }
}

// MARK: - 结论卡

private struct ResultHero: View {
    let report: ReportModel
    var body: some View {
        let tone = ReportTone.level(report.level)
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 10) {
                    Text(report.badge).font(EyeStyle.chip).foregroundStyle(EyeStyle.onStatus)
                        .padding(.horizontal, 12).padding(.vertical, 6).background(tone.0, in: Capsule())
                    // 长结论用标题字号，避免大字折成三行；与安卓 LONG_TITLE 一致
                    Text(report.title).font(report.title.count > 8 ? EyeStyle.title : EyeStyle.headline).foregroundStyle(EyeStyle.text)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                MoodMascot(mood: report.mood).frame(width: 104, height: 104)
            }
            Text(report.advice).font(EyeStyle.body).foregroundStyle(EyeStyle.text).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 20).padding(.vertical, 22)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LinearGradient(colors: [tone.1, EyeStyle.panel], startPoint: .top, endPoint: .bottom),
            in: RoundedRectangle(cornerRadius: 22))
    }
}

/// 小瞳的神情：放心（笑）、思考（看向一侧）、担心（睁大）、没测好（眯眼）。
private struct MoodMascot: View {
    let mood: String
    var body: some View {
        Canvas { context, size in
            let s = min(size.width, size.height)
            let center = CGPoint(x: size.width / 2, y: size.height * 0.58)
            switch mood {
            case "THINKING": Cartoon.mascot(context, center: center, size: s * 0.8, gaze: CGPoint(x: 0.6, y: -0.4), smile: false)
            case "CONCERNED": Cartoon.mascot(context, center: center, size: s * 0.8, wide: 1.15, smile: false)
            case "SLEEPY": Cartoon.mascot(context, center: center, size: s * 0.8, lid: 0.85)
            default: Cartoon.mascot(context, center: center, size: s * 0.8)
            }
        }.accessibilityLabel("小瞳")
    }
}

// MARK: - 指标与依据

private struct MetricGrid: View {
    let metrics: [ReportModel.Metric]
    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
            ForEach(metrics) { m in
                let tone = ReportTone.status(m.status)
                VStack(alignment: .leading, spacing: 6) {
                    Text(m.label).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary).lineLimit(1)
                    Text(m.value).font(EyeStyle.title).foregroundStyle(m.status == "ABNORMAL" ? EyeStyle.warning : EyeStyle.text)
                        .lineLimit(1).minimumScaleFactor(0.7)
                    Text(m.status == "UNAVAILABLE" ? "参考" : ReportTone.label(m.status)).font(EyeStyle.chip).foregroundStyle(tone.0)
                        .padding(.horizontal, 10).padding(.vertical, 4).background(tone.1, in: Capsule())
                }
                .padding(16).frame(maxWidth: .infinity, alignment: .leading)
                .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radiusLarge))
            }
        }
    }
}

private struct EvidenceCard: View {
    let evidence: [ReportModel.Evidence]
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("判断依据").font(EyeStyle.subtitle).padding(.bottom, 6)
            ForEach(Array(evidence.enumerated()), id: \.element.id) { index, item in
                if index > 0 { Rectangle().fill(EyeStyle.borderSubtle).frame(height: 1) }
                EvidenceRow(item: item)
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
    }
}

private struct EvidenceRow: View {
    let item: ReportModel.Evidence
    @State private var expanded = false
    var body: some View {
        let tone = ReportTone.status(item.status)
        VStack(alignment: .leading, spacing: 10) {
            Button {
                withAnimation(.easeInOut(duration: 0.25)) { expanded.toggle() }
            } label: {
                HStack(spacing: 12) {
                    ZStack { Circle().fill(tone.1); Circle().fill(tone.0).frame(width: 10, height: 10) }.frame(width: 28, height: 28)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 8) {
                            Text(item.title).font(EyeStyle.button).foregroundStyle(EyeStyle.text)
                            Text(ReportTone.label(item.status)).font(EyeStyle.chip).foregroundStyle(tone.0)
                        }
                        Text(item.summary).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                            .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 4)
                    if !item.details.isEmpty {
                        Image(systemName: "chevron.right").foregroundStyle(EyeStyle.primary)
                            .rotationEffect(.degrees(expanded ? 90 : 0))
                    }
                }.contentShape(Rectangle())
            }
            .buttonStyle(.plain).disabled(item.details.isEmpty)
            .accessibilityValue(expanded ? "已展开" : "已收起")
            if expanded {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(item.details, id: \.self) { Text($0).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.text) }
                }
                .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                .background(EyeStyle.surfaceVariant, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
                .padding(.leading, 40)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }.padding(.vertical, 12)
    }
}

private struct ProblemCard: View {
    let problems: [String]
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("没测好的原因").font(EyeStyle.subtitle)
            ForEach(problems, id: \.self) { item in
                HStack(alignment: .top, spacing: 10) {
                    Circle().fill(EyeStyle.secondary).frame(width: 8, height: 8).padding(.top, 7)
                    Text(item).font(EyeStyle.body).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(20).frame(maxWidth: .infinity, alignment: .leading)
        .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: 22))
    }
}

// MARK: - 本次测试数据

private struct ReportDataSheet: View {
    let report: ReportModel
    let exportURL: URL?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    if !report.evidence.isEmpty {
                        DataPanel(title: "判断依据") {
                            ForEach(report.evidence) { e in
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("\(e.title) · \(ReportTone.label(e.status))").font(EyeStyle.button)
                                    Text(e.summary).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                                    ForEach(e.details, id: \.self) { Text("· \($0)").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary) }
                                }.frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    }
                    ForEach(report.sections) { section in
                        DataPanel(title: section.title) {
                            ForEach(section.rows) { row in
                                if row.label.isEmpty {
                                    Text(row.value).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                } else {
                                    HStack(alignment: .firstTextBaseline) {
                                        Text(row.label).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                        Text(row.value).font(EyeStyle.body).frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                }
                            }
                        }
                    }
                    Text("评估规则：\(report.version)").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }.padding(16)
            }
            .background(EyeStyle.background.ignoresSafeArea())
            .navigationTitle("本次测试数据").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } }
                if let url = exportURL {
                    ToolbarItem(placement: .primaryAction) { ShareLink(item: url) { Text("导出") } }
                }
            }
        }
        .foregroundStyle(EyeStyle.text).tint(EyeStyle.primary)
        .presentationDetents([.large])
    }
}

private struct DataPanel<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(EyeStyle.subtitle)
            content
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radiusLarge))
    }
}
