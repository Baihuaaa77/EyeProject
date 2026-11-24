import SwiftUI
import UIKit

/// 用眼全记录和指标详情共用所选日期。详情返回后保留轮播位置。
struct DailyRecordsView: View {
    let report: CareReport
    let onClose: () -> Void

    @State private var selectedDate: String?
    @State private var visibleMetric = 0
    @State private var selectedMetric: Int?
    @GestureState private var drag: CGFloat = 0
    @ScaledMetric(relativeTo: .title) private var cardHeight: CGFloat = 128
    @Environment(\.dynamicTypeSize) private var textSize

    private static let garden: UIImage? = Bundle.main.url(forResource: "v16_pressure_garden", withExtension: "jpg")
        .flatMap { UIImage(contentsOfFile: $0.path) }

    var body: some View {
        let records = report.dailyRecords
        let dateIndex = records.firstIndex { $0.id == selectedDate } ?? records.indices.last
        let day = dateIndex.map { records[$0] }

        NavigationStack {
            PageScroll {
                if let day, let dateIndex {
                    HStack(spacing: EyeSpacing.xs) {
                        arrow("前一天", icon: "chevron.left", enabled: dateIndex > 0) { selectedDate = records[dateIndex - 1].id }
                        Text(day.dateLabel).font(EyeStyle.subtitle).multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                        arrow("后一天", icon: "chevron.right", enabled: dateIndex < records.count - 1) { selectedDate = records[dateIndex + 1].id }
                    }
                    weekStrip(day, available: Set(records.map(\.id)))
                    pressureCard(day)
                    if !day.metrics.isEmpty { carousel(day.metrics) }
                } else {
                    Text("暂无用眼记录").font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                }
            }
            .foregroundStyle(EyeStyle.text)
            .navigationTitle("用眼全记录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(action: onClose) { Image(systemName: "chevron.left") }
                        .accessibilityLabel("返回")
                }
            }
            .navigationDestination(isPresented: Binding(
                get: { selectedMetric != nil },
                set: { if !$0 { selectedMetric = nil } }
            )) {
                if let day, let index = selectedMetric, day.metrics.indices.contains(index) {
                    let metric = day.metrics[index]
                    PageScroll {
                        Text(day.dateLabel).font(EyeStyle.bodySecondary)
                        reading(metric)
                        Text(metric.note).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                    }
                    .foregroundStyle(EyeStyle.text)
                    .navigationTitle(metric.title)
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbarBackground(EyeStyle.background, for: .navigationBar)
                    .toolbarBackground(.visible, for: .navigationBar)
                }
            }
            .toolbarBackground(EyeStyle.background, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
        }
        .tint(EyeStyle.primary)
    }

    private func weekStrip(_ day: CareReport.DailyRecord, available: Set<String>) -> some View {
        let calendar = Calendar(identifier: .iso8601)
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        let monday = formatter.date(from: day.id).flatMap { calendar.dateInterval(of: .weekOfYear, for: $0)?.start }
        let week = (0..<7).compactMap { index in monday.flatMap { calendar.date(byAdding: .day, value: index, to: $0) } }
        let weekdays = ["一", "二", "三", "四", "五", "六", "日"]
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 0) {
                ForEach(week.indices, id: \.self) { index in
                    let date = week[index]
                    let key = formatter.string(from: date)
                    let current = key == day.id
                    let enabled = available.contains(key)
                    let components = calendar.dateComponents([.month, .day], from: date)
                    Button { selectedDate = key } label: {
                        VStack(spacing: 0) {
                            Text("周\(weekdays[index])").font(EyeStyle.caption)
                            Text("\(components.day ?? 0)").font(EyeStyle.subtitle).monospacedDigit()
                        }
                        .frame(minWidth: 44, minHeight: 44)
                        .padding(.vertical, EyeSpacing.xxs)
                        .foregroundStyle(current ? EyeStyle.onPrimary : enabled ? EyeStyle.text : EyeStyle.tertiary)
                        .background(current ? EyeStyle.primary : Color.clear, in: RoundedRectangle(cornerRadius: EyeStyle.radiusMedium))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!enabled)
                    .accessibilityLabel("\(components.month ?? 0)月\(components.day ?? 0)日，周\(weekdays[index])")
                    .accessibilityAddTraits(current ? .isSelected : [])
                }
            }
        }
    }

    private func pressureCard(_ day: CareReport.DailyRecord) -> some View {
        let copy: (title: String, tip: String?)
        switch day.score {
        case .some(let score) where score < 30: copy = ("当日用眼压力较轻", "请继续保持")
        case .some(let score) where score < 60: copy = ("当日用眼压力适中", "记得间歇休息")
        case .some: copy = ("当日用眼压力较重", "请注意休息")
        case .none: copy = ("当日暂无负荷记录", nil)
        }
        return VStack(spacing: EyeSpacing.xxs) {
            Text(copy.title).font(EyeStyle.title).foregroundStyle(BrownStyle.pressureInk)
            if let tip = copy.tip {
                Text(tip).font(EyeStyle.body).foregroundStyle(BrownStyle.pressureText)
            }
        }
        .multilineTextAlignment(.center)
        .padding(.horizontal, EyeSpacing.xxl)
        .padding(.vertical, EyeSpacing.lg)
        .frame(maxWidth: .infinity, minHeight: 128)
        .background {
            GeometryReader { geometry in
                if let garden = Self.garden {
                    Image(uiImage: garden).resizable().scaledToFill()
                        .frame(width: geometry.size.width, height: geometry.size.height)
                        .accessibilityHidden(true)
                } else {
                    BrownStyle.pressurePaper
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: EyeStyle.radius))
    }

    private func carousel(_ metrics: [CareReport.DailyMetric]) -> some View {
        let count = metrics.count
        let current = wrapped(visibleMetric, count: count)
        return VStack(spacing: EyeSpacing.xs) {
            HStack(spacing: EyeSpacing.xxs) {
                Text("当日读数").font(EyeStyle.subtitle)
                Spacer(minLength: 0)
                arrow("上一项", icon: "chevron.left", enabled: count > 1) { visibleMetric = wrapped(current - 1, count: count) }
                Text("\(current + 1)/\(count)").font(EyeStyle.caption).monospacedDigit()
                arrow("下一项", icon: "chevron.right", enabled: count > 1) { visibleMetric = wrapped(current + 1, count: count) }
            }
            GeometryReader { geometry in
                let gap = EyeSpacing.xs
                let width = textSize.isAccessibilitySize ? geometry.size.width : max(1, (geometry.size.width - gap) * 2 / 3)
                let step = width + gap
                HStack(spacing: gap) {
                    ForEach(-1...2, id: \.self) { offset in
                        let index = wrapped(current + offset, count: count)
                        Button {
                            visibleMetric = index
                            selectedMetric = index
                        } label: {
                            VStack(alignment: .leading, spacing: EyeSpacing.xxs) {
                                HStack(alignment: .top) {
                                    Text(metrics[index].title).font(EyeStyle.subtitle)
                                    Spacer(minLength: 0)
                                    Image(systemName: "chevron.right").font(EyeStyle.caption)
                                        .foregroundStyle(EyeStyle.glassEdgeTop).accessibilityHidden(true)
                                }
                                reading(metrics[index])
                            }
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                            .padding(EyeSpacing.md)
                            .background(BrownStyle.surface, in: RoundedRectangle(cornerRadius: EyeStyle.radius))
                            .overlay { RoundedRectangle(cornerRadius: EyeStyle.radius).strokeBorder(EyeStyle.glassEdgeTop) }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .frame(width: width)
                        .accessibilityHidden(offset != 0)
                    }
                }
                .fixedSize(horizontal: true, vertical: false)
                .offset(x: -step + max(-step, min(step, drag)))
                .frame(width: geometry.size.width, alignment: .leading)
                .clipped()
                .contentShape(Rectangle())
                .simultaneousGesture(DragGesture(minimumDistance: 14)
                    .updating($drag) { value, state, _ in
                        if count > 1, abs(value.translation.width) > abs(value.translation.height) {
                            state = value.translation.width
                        }
                    }
                    .onEnded { value in
                        let horizontal = value.translation.width
                        guard count > 1, abs(horizontal) > max(28, abs(value.translation.height)) else { return }
                        visibleMetric = wrapped(current + (horizontal < 0 ? 1 : -1), count: count)
                    })
            }
            .frame(height: cardHeight)
        }
    }

    private func reading(_ metric: CareReport.DailyMetric) -> some View {
        EyeStyle.reading(metric.value ?? "—", numberFont: EyeStyle.display, color: EyeStyle.text, unitColor: EyeStyle.text)
            .lineLimit(2)
            .minimumScaleFactor(0.65)
            .foregroundStyle(EyeStyle.text)
    }

    private func arrow(_ title: String, icon: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(EyeStyle.body)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(title)
    }

    private func wrapped(_ index: Int, count: Int) -> Int { (index % count + count) % count }
}
