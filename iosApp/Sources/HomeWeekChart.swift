import SwiftUI

struct HomeWeekChart: View {
    let history: [CareReport.Day]
    let onFeeling: () -> Void
    let onTrend: () -> Void
    @State private var pickedWeek: Int?
    @State private var chosen: Int?
    @Environment(\.dynamicTypeSize) private var textSize
    private var week: Int {
        if let pickedWeek { return pickedWeek }
        let thisWeek = monday(weeksBack: 0)
        let previousWeek = monday(weeksBack: 1)
        let startKey = dayKey(previousWeek)
        let endKey = dayKey(thisWeek)
        return history.contains {
            $0.screenMinutes != nil && $0.id >= startKey && $0.id < endKey
        } ? 1 : 0
    }
    private let labels = ["一", "二", "三", "四", "五", "六", "日"]

    var body: some View {
        let days = weekDays
        let selected = chosen ?? days.lastIndex { $0?.screenMinutes != nil }
        let reading = selected.flatMap { days[$0] }
        WarmCard(fill: BrownStyle.homeSurface, padding: 14, spacing: 4, edge: BrownStyle.homeEdge) {
            HStack {
                Text("用眼爱记录").font(EyeStyle.subtitle)
                Spacer(minLength: 0)
                Button("记录感受", action: onFeeling).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.text).frame(minHeight: 44)
            }
            let range = Text(weekTitle).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            if textSize.isAccessibilitySize { range; weekPicker }
            else { HStack { range; Spacer(); weekPicker.frame(width: 108) } }
            HStack {
                Text((selected.map { "周\(labels[$0])" } ?? "") + "用屏").font(EyeStyle.bodySecondary)
                if let score = reading?.score {
                    Text(score < 30 ? "轻度" : (score < 60 ? "中度" : "重度"))
                        .font(EyeStyle.bodySecondary).foregroundStyle(tone(reading))
                }
                Spacer(minLength: 6)
                EyeStyle.reading(reading?.screenMinutes.map { "\($0 / 60)小时\($0 % 60)分" } ?? "—", color: tone(reading))
            }
            let peak = max(120, ((days.compactMap { $0?.screenMinutes }.max() ?? 0) + 119) / 120 * 120)
            HStack(spacing: 6) {
                VStack(alignment: .trailing) {
                    Text("\(peak / 60)时")
                    Spacer()
                    Text("\(peak / 120)时")
                    Spacer()
                    Text("0时")
                }.font(EyeStyle.caption).foregroundStyle(EyeStyle.secondary)
                    .frame(width: 32, height: 100, alignment: .trailing)
                chart(days, selected: selected, peak: peak)
            }
            HStack(spacing: 0) {
                Spacer().frame(width: 38)
                ForEach(labels.indices, id: \.self) { index in
                    Button { chosen = index } label: {
                        Text(labels[index]).font(EyeStyle.caption)
                            .foregroundStyle(index == selected ? tone(days[index]) : EyeStyle.secondary)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("星期" + labels[index])
                    .accessibilityAddTraits(index == selected ? .isSelected : [])
                }
            }
            HStack {
                let values = days.compactMap { $0?.screenMinutes }
                let average = values.reduce(0, +) / max(1, values.count)
                Text(values.isEmpty ? "暂无用屏记录" : "有记录的日子平均用屏 \(average / 60)小时\(average % 60)分")
                    .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                Spacer(minLength: 6)
                Button("用眼报告 ›", action: onTrend).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.text).frame(minHeight: 44)
            }
        }
    }

    private var weekPicker: some View {
        SegmentedPicker(options: [(1, "上周"), (0, "本周")], selected: week, warm: true) { pickedWeek = $0; chosen = nil }
    }

    private var localCalendar: Calendar {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current
        return calendar
    }

    private var monday: Date { monday(weeksBack: week) }

    private func monday(weeksBack: Int) -> Date {
        let today = Date()
        let calendar = localCalendar
        let start = calendar.dateInterval(of: .weekOfYear, for: today)?.start ?? today
        return calendar.date(byAdding: .day, value: -weeksBack * 7, to: start) ?? start
    }

    private var weekTitle: String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.calendar = localCalendar
        formatter.dateFormat = "M月d日"
        let end = localCalendar.date(byAdding: .day, value: 6, to: monday) ?? monday
        return formatter.string(from: monday) + "–" + formatter.string(from: end)
    }

    private var weekDays: [CareReport.Day?] {
        let calendar = localCalendar
        let today = Date()
        return labels.indices.map { index in
            guard let date = calendar.date(byAdding: .day, value: index, to: monday),
                  date <= today else { return nil }
            let key = dayKey(date)
            return history.first { $0.id == key }
        }
    }

    private func dayKey(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = localCalendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    private func tone(_ day: CareReport.Day?) -> Color {
        day?.score.map(BrownStyle.loadColor) ?? EyeStyle.secondary
    }

    private func chart(_ days: [CareReport.Day?], selected: Int?, peak: Int) -> some View {
        GeometryReader { geometry in
            Canvas { context, size in
                for y in [8.0, size.height / 2, size.height - 8] {
                    let grid = Path {
                        $0.move(to: CGPoint(x: 0, y: y))
                        $0.addLine(to: CGPoint(x: size.width, y: y))
                    }
                    context.stroke(grid, with: .color(EyeStyle.borderSubtle), lineWidth: 1)
                }
                let points: [CGPoint?] = days.enumerated().map { index, day in
                    day?.screenMinutes.map { minutes in
                        CGPoint(x: size.width * (CGFloat(index) + 0.5) / 7,
                                y: size.height - 8 - (size.height - 16) * CGFloat(minutes) / CGFloat(peak))
                    }
                }
                for index in 0..<6 {
                    guard let first = points[index], let next = points[index + 1] else { continue }
                    let color = tone(days[index])
                    let light = Path { path in
                        path.move(to: first)
                        path.addLine(to: next)
                        path.addLine(to: CGPoint(x: next.x, y: size.height))
                        path.addLine(to: CGPoint(x: first.x, y: size.height))
                        path.closeSubpath()
                    }
                    context.fill(light, with: .linearGradient(
                        Gradient(colors: [color.opacity(0.4), color.opacity(0)]),
                        startPoint: CGPoint(x: 0, y: min(first.y, next.y)),
                        endPoint: CGPoint(x: 0, y: size.height)))
                    let segment = Path {
                        $0.move(to: first)
                        $0.addLine(to: next)
                    }
                    context.stroke(segment, with: .color(color), lineWidth: 2)
                }
                for (index, point) in points.enumerated() {
                    guard let point else { continue }
                    let color = tone(days[index])
                    if index == selected {
                        let start = CGPoint(x: point.x - 9, y: point.y)
                        let end = CGPoint(x: point.x + 9, y: point.y)
                        let eye = Path {
                            $0.move(to: start)
                            $0.addQuadCurve(to: end, control: CGPoint(x: point.x, y: point.y - 10))
                            $0.addQuadCurve(to: start, control: CGPoint(x: point.x, y: point.y + 10))
                            $0.closeSubpath()
                        }
                        context.fill(eye, with: .color(color))
                        context.fill(Path(ellipseIn: CGRect(x: point.x - 3, y: point.y - 3, width: 6, height: 6)), with: .color(EyeStyle.background))
                    } else {
                        context.fill(Path(ellipseIn: CGRect(x: point.x - 3, y: point.y - 3, width: 6, height: 6)), with: .color(color))
                    }
                }
            }
            .contentShape(Rectangle())
            .onTapGesture(coordinateSpace: .local) { point in
                chosen = min(6, max(0, Int(7 * point.x / max(1, geometry.size.width))))
            }
        }
        .frame(height: 100)
        .accessibilityHidden(true)
    }
}
