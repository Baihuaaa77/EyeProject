import SwiftUI

/// 结果页：内容来自共用核心的 ResultReport（快照里的 "report"），与安卓 ReportComponents 同一排版（2026-11-08 改版）：
/// 结论卡（浅底等级徽标 + 一句结论 + 风险分段条 + 一句建议）→「指标」列表行 →「判断依据」（点开看明细）→ 数据可靠性小字 → 操作 →
/// 「查看本次数据」「导出数据」→ 一行说明。首屏不放成段小字，明细都在数据页里。
struct ResultReportView: View {
    @ObservedObject var runtime: EyeRuntime
    @State private var dataOpen = false

    var body: some View {
        if let report = ReportModel(runtime.state["report"] as? [String: Any]) {
            VStack(alignment: .leading, spacing: 12) {
                ResultHero(report: report)
                if !report.valid {
                    ProblemCard(problems: report.problems)
                } else {
                    MetricGrid(metrics: report.metrics)
                    if !report.evidence.isEmpty { EvidenceCard(evidence: report.evidence) }
                }
                // 数据可靠性：不再单独成卡，作为依据下方的一段小字（数据无效时原因已在 ProblemCard 列出）
                if report.valid && !report.qualityNotes.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("数据可靠性：" + report.qualityNotes[0]).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                        if report.qualityNotes.count > 1 {
                            DisclosureGroup("查看说明") {
                                ForEach(Array(report.qualityNotes.dropFirst().enumerated()), id: \.offset) { _, note in
                                    Text(note).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                                }
                            }.font(EyeStyle.bodySecondary).tint(EyeStyle.primary)
                        }
                    }.padding(.horizontal, 4).frame(maxWidth: .infinity, alignment: .leading)
                }
                // 解读放在规则结论与依据之后：先看规则判了什么，再看通俗解释
                if !runtime.string("savedFile").isEmpty { AiExplanationView(fileName: runtime.string("savedFile")) }
                actions(report)
                // 记录没写进本机：明确告诉用户，免得以为已经存进历史（与安卓一致）
                if !runtime.string("saveError").isEmpty {
                    Text(runtime.string("saveError")).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.readableText(EyeStyle.danger))
                        .padding(.horizontal, 4).frame(maxWidth: .infinity, alignment: .leading)
                }
                HStack(spacing: 10) {
                    Button("查看本次数据") { dataOpen = true }.buttonStyle(EyeButtonStyle(role: .secondary))
                    if let file = exportURL {
                        ShareLink(item: file) { Text("导出数据") }.buttonStyle(EyeButtonStyle(role: .secondary))
                    }
                }
                Text(report.footnote).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            // 「查看本次数据」以完整页面呈现（不用底部弹窗）
            .fullScreenCover(isPresented: $dataOpen) { ReportDataSheet(report: report, exportURL: exportURL) }
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
        // 老视：允许换一种戴镜状态重复检查，各次独立记录、分别解释（与安卓一致）
        if runtime.kind == "PRESBYOPIA" && !runtime.string("subjectId").isEmpty {
            Button("换一种戴镜方式再测") { runtime.act("otherGlasses") }.buttonStyle(EyeButtonStyle(role: .secondary))
        }
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
    /// 结论卡上的突出结果（老视「你的眼睛年龄」）：名称、数值、说明；没有时为 nil。
    let highlight: (label: String, value: String, note: String)?

    init?(_ dict: [String: Any]?) {
        guard let d = dict, let level = d["level"] as? String else { return nil }
        self.level = level
        badge = d["badge"] as? String ?? ""; title = d["title"] as? String ?? ""
        advice = d["advice"] as? String ?? ""; mood = d["mood"] as? String ?? "HAPPY"
        footnote = d["footnote"] as? String ?? ""; version = d["version"] as? String ?? ""
        valid = d["valid"] as? Bool ?? false
        qualityNotes = d["qualityNotes"] as? [String] ?? []
        if let h = d["highlight"] as? [String: Any], let value = h["value"] as? String {
            highlight = (h["label"] as? String ?? "", value, h["note"] as? String ?? "")
        } else {
            highlight = nil
        }
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
        case "NORMAL": return "正常"; case "ABNORMAL": return "异常"; case "UNCERTAIN": return "低风险"; default: return "无法评估"
        }
    }
}

// MARK: - 结论卡

private struct ResultHero: View {
    let report: ReportModel
    var body: some View {
        let tone = ReportTone.level(report.level)
        EyeHeroCard(spacing: 14) {
            Text(report.badge).font(EyeStyle.chip).foregroundStyle(EyeStyle.text)
                .padding(.horizontal, 10).padding(.vertical, 4).background(tone.1, in: EyeStyle.chipShape)
            // 长结论用标题字号，避免大字折成三行；与安卓 LONG_TITLE 一致
            Text(report.title).font(report.title.count > 8 ? EyeStyle.title : EyeStyle.headline).foregroundStyle(EyeStyle.text)
                .fixedSize(horizontal: false, vertical: true)
            if let highlight = report.highlight {
                VStack(alignment: .leading, spacing: 4) {
                    Rectangle().fill(EyeStyle.borderSubtle).frame(height: 1).padding(.bottom, 8)
                    Text(highlight.label).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    Text(highlight.value).font(EyeStyle.headline).foregroundStyle(EyeStyle.readableText(EyeStyle.primary))
                    Text(highlight.note).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
            }
            RiskSegments(level: report.level)
            Text(report.advice).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary).fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// 风险分段条：正常 → 低风险 → 中风险 → 高风险四段，当前等级那段用等级色；无法评估时全灰。与安卓 RiskSegments 一致。
private struct RiskSegments: View {
    let level: String
    var body: some View {
        HStack(spacing: 4) {
            ForEach(["LOW", "UNCERTAIN", "MEDIUM", "HIGH"], id: \.self) { step in
                let on = step == level
                let color = EyeStyle.statusFill(ReportTone.level(step).0).0
                Capsule().fill(on ? color : EyeStyle.surfaceVariant).frame(height: 6)
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - 指标与依据

/// 分区标题（卡片外、20 号半粗）。
private struct ReportSectionTitle: View {
    let text: String
    var body: some View { Text(text).font(EyeStyle.title).foregroundStyle(EyeStyle.text).padding(.top, 16).padding(.leading, 4) }
}

/// 关键指标：一组列表行，左边名称，右边「大号数值 + 灰色单位」与其下的状态（小圆点 + 状态色文字）。与安卓 MetricGrid 一致。
private struct MetricGrid: View {
    let metrics: [ReportModel.Metric]
    var body: some View {
        if !metrics.isEmpty {
            ReportSectionTitle(text: "指标")
            VStack(spacing: 0) {
                ForEach(Array(metrics.enumerated()), id: \.element.id) { i, m in
                    row(m)
                    if i < metrics.count - 1 { Rectangle().fill(EyeStyle.borderSubtle).frame(height: 0.5).padding(.leading, 16) }
                }
            }.eyeGlass()
        }
    }
    private func row(_ m: ReportModel.Metric) -> some View {
        let strong = m.status == "UNAVAILABLE" ? EyeStyle.secondary : ReportTone.status(m.status).0
        let dot = m.status == "ABNORMAL" ? EyeStyle.accent : m.status == "UNAVAILABLE" ? EyeStyle.tertiary : strong
        return HStack(spacing: 12) {
            Text(m.label).font(EyeStyle.body).foregroundStyle(EyeStyle.text).frame(maxWidth: .infinity, alignment: .leading)
            VStack(alignment: .trailing, spacing: 2) {
                EyeStyle.reading(m.value, numberFont: Font.system(size: 17, weight: .semibold, design: .rounded)).lineLimit(1)
                HStack(spacing: 5) {
                    Circle().fill(dot).frame(width: 6, height: 6)
                    Text(m.status == "UNAVAILABLE" ? "参考" : ReportTone.label(m.status)).font(EyeStyle.caption).foregroundStyle(strong)
                }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .accessibilityElement(children: .combine)
    }
}

private struct EvidenceCard: View {
    let evidence: [ReportModel.Evidence]
    var body: some View {
        ReportSectionTitle(text: "判断依据")
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(evidence.enumerated()), id: \.element.id) { index, item in
                if index > 0 { Rectangle().fill(EyeStyle.borderSubtle).frame(height: 0.5) }
                EvidenceRow(item: item)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .eyeGlass()
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
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.title).font(EyeStyle.body.weight(.medium)).foregroundStyle(EyeStyle.text)
                        Text(item.summary).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                            .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    Text(ReportTone.label(item.status)).font(EyeStyle.chip).foregroundStyle(EyeStyle.text)
                    if !item.details.isEmpty {
                        Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(EyeStyle.tertiary)
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
                .background(EyeStyle.surfaceVariant, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium, style: .continuous))
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
        .eyeGlass()
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
        .eyeGlass(cornerRadius: EyeStyle.radiusLarge)
    }
}

#if SCREENSHOT_CAPTURE
extension ResultReportView {
    @ViewBuilder static func captureData(_ value: [String: Any]) -> some View {
        if let model = ReportModel(value) { ReportDataSheet(report: model, exportURL: nil) }
    }
}
#endif
