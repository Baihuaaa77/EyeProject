import SwiftUI

struct DailyRecordsView: View {
    let report: CareReport
    let onClose: () -> Void
    @State private var selectedDate: String?
    @State private var selectedMetric = 0
    @State private var detail: CareReport.DailyMetric?

    private var dateIndex: Int? {
        report.dailyRecords.firstIndex { $0.id == selectedDate } ?? report.dailyRecords.indices.last
    }

    var body: some View {
        SubPage(title: "用眼全记录") {
            if let dateIndex {
                let day = report.dailyRecords[dateIndex]
                Text(day.dateLabel).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
                HStack(spacing: EyeSpacing.itemGap) {
                    Button("前一天") { moveDate(from: dateIndex, by: -1) }
                        .buttonStyle(EyeButtonStyle(role: .secondary)).disabled(dateIndex == 0)
                    Button("后一天") { moveDate(from: dateIndex, by: 1) }
                        .buttonStyle(EyeButtonStyle(role: .secondary)).disabled(dateIndex == report.dailyRecords.count - 1)
                }
                WarmCard {
                    Text("用眼负荷").font(EyeStyle.subtitle)
                    Text(day.score.map { "\($0) / 100" } ?? "暂无记录").font(EyeStyle.display)
                        .foregroundStyle(day.score.map(BrownStyle.loadColor) ?? EyeStyle.secondary)
                    if day.score != nil, let tip = day.tip {
                        Text(tip).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    }
                }
                if !day.metrics.isEmpty { metricPager(day.metrics) }
            } else {
                WarmCard { Text("正在读取记录…").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary) }
            }
        }
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                if detail == nil { Button("关闭", action: onClose) }
            }
        }
        .navigationDestination(isPresented: Binding(
            get: { detail != nil }, set: { if !$0 { detail = nil } }
        )) {
            if let detail, let dateIndex {
                SubPage(title: detail.title) {
                    WarmCard {
                        Text(report.dailyRecords[dateIndex].dateLabel).font(EyeStyle.bodySecondary)
                        Text(detail.value ?? "暂无记录").font(EyeStyle.display)
                        Text(detail.note).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    }
                }
            }
        }
    }

    private func moveDate(from index: Int, by step: Int) {
        let next = index + step
        if report.dailyRecords.indices.contains(next) { selectedDate = report.dailyRecords[next].id }
    }

    private func metricPager(_ metrics: [CareReport.DailyMetric]) -> some View {
        let index = min(selectedMetric, metrics.count - 1)
        let metric = metrics[index]
        return VStack(alignment: .leading, spacing: EyeSpacing.itemGap) {
            Button { detail = metric } label: {
                WarmCard {
                    Text(metric.title).font(EyeStyle.navigationTitle)
                    Text(metric.value ?? "暂无记录").font(EyeStyle.display)
                }
                .foregroundStyle(EyeStyle.text)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .simultaneousGesture(DragGesture(minimumDistance: 30).onEnded { gesture in
                guard abs(gesture.translation.width) > abs(gesture.translation.height) else { return }
                selectedMetric = max(0, min(index + (gesture.translation.width < 0 ? 1 : -1), metrics.count - 1))
            })
            HStack(spacing: EyeSpacing.itemGap) {
                Button("上一项") { selectedMetric = index - 1 }
                    .buttonStyle(EyeButtonStyle(role: .secondary)).disabled(index == 0)
                Button("下一项") { selectedMetric = index + 1 }
                    .buttonStyle(EyeButtonStyle(role: .secondary)).disabled(index == metrics.count - 1)
            }
            Text("\(index + 1) / \(metrics.count)").font(EyeStyle.caption).foregroundStyle(EyeStyle.secondary)
        }
    }
}
