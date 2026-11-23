import SwiftUI

/// 首页原位切换日期和展开记录，复用真实采集的数据。
struct DailyRecordsView: View {
    let report: CareReport
    let onClose: () -> Void
    @State private var selectedDate: String?
    @State private var selectedMetric: Int?
    @Environment(\.dynamicTypeSize) private var textSize

    var body: some View {
        let records = report.dailyRecords
        let index = records.firstIndex { $0.id == selectedDate } ?? records.indices.last
        let day = index.map { records[$0] }
        WarmCard(fill: BrownStyle.homeSurface, padding: 14, edge: BrownStyle.homeEdge) {
            HStack {
                Text(day?.dateLabel ?? "用眼全记录").font(EyeStyle.subtitle)
                Spacer()
                Button("收起", action: onClose).font(EyeStyle.bodySecondary).frame(minHeight: 44)
            }
            if let day, let index {
                HStack(spacing: 8) {
                    Button("前一天") { select(index - 1) }.buttonStyle(EyeButtonStyle(role: .secondary)).disabled(index == 0)
                    Button("后一天") { select(index + 1) }.buttonStyle(EyeButtonStyle(role: .secondary)).disabled(index >= records.count - 1)
                }
                let accents = [EyeStyle.feelingComfort, EyeStyle.feelingDry, EyeStyle.feelingSore, EyeStyle.feelingTired]
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: textSize.isAccessibilitySize ? 2 : 3), spacing: 6) {
                    ForEach(day.metrics.indices, id: \.self) { i in
                        let metric = day.metrics[i]
                        let accent = accents[i % accents.count]
                        Button { selectedMetric = selectedMetric == i ? nil : i } label: {
                            WarmCard(padding: 8, edge: accent.opacity(selectedMetric == i ? 0.9 : 0.35)) {
                                Text(metric.title).font(EyeStyle.caption).foregroundStyle(EyeStyle.secondary)
                                Text(metric.value ?? "—").font(EyeStyle.subtitle).foregroundStyle(metric.value == nil ? EyeStyle.secondary : accent)
                            }.frame(minHeight: 44)
                        }.buttonStyle(.plain)
                        .accessibilityAddTraits(selectedMetric == i ? .isSelected : [])
                    }
                }
                if let note = selectedMetric.flatMap({ day.metrics.indices.contains($0) ? day.metrics[$0].note : nil }) ?? day.tip {
                    Text(note).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                }
            } else {
                Text("暂无用眼记录").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
            }
        }
    }

    private func select(_ index: Int) {
        guard report.dailyRecords.indices.contains(index) else { return }
        selectedDate = report.dailyRecords[index].id
        selectedMetric = nil
    }
}
