import SwiftUI

/// 眨眼趋势按周与星期查看，缺测日期保持断点。
struct V16WeeklyTrendView: View {
    let report: CareReport
    @State private var week = 0
    @State private var selected = (Calendar.current.component(.weekday,from: Date())+5)%7
    private var calendar: Calendar { var value = Calendar(identifier: .iso8601); value.timeZone = .current; return value }
    private var today: Date {
        let formatter = DateFormatter(); formatter.calendar = calendar; formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.dateFormat = "yyyy-MM-dd"
        return report.days.last.flatMap { formatter.date(from: $0.id) } ?? Date()
    }
    private var dates: [Date] {
        let start = calendar.date(from: calendar.dateComponents([.yearForWeekOfYear,.weekOfYear],from: today)) ?? Date()
        let monday = calendar.date(byAdding: .day,value: -7*week,to: start) ?? start
        return (0..<7).map { calendar.date(byAdding: .day,value: $0,to: monday) ?? monday }
    }
    private func key(_ date: Date) -> String {
        let formatter = DateFormatter(); formatter.calendar = calendar; formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"; return formatter.string(from: date)
    }
    private var days: [CareReport.Day?] {
        let history = report.historyDays.isEmpty ? report.days : report.historyDays
        return dates.map { date in history.first { $0.id == key(date) } }
    }
    private var blinkValues: [Double?] {
        days.map { $0?.blinksPerMinute }
    }
    var body: some View {
        SegmentedPicker(options: [(0,"本周"),(1,"上周")],selected: week) { week = $0 }
        Text("\(key(dates[0])) — \(key(dates[6]))").font(EyeStyle.subtitle)
        weekdays
        WarmCard {
            Text("平均眨眼频率").font(EyeStyle.subtitle)
            let values = blinkValues.compactMap { $0 }
            if values.isEmpty {
                Text(week == 0 ? "本周暂无眨眼记录" : "上周暂无眨眼记录").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            } else {
                Text("\(Int((values.reduce(0, +) / Double(values.count)).rounded())) 次/分")
                    .font(EyeStyle.metricValue).monospacedDigit()
                line(blinkValues).frame(height: 112).accessibilityHidden(true)
                Text("\(key(dates[selected])) · \(blinkValues[selected].map { "\(Int($0.rounded())) 次/分" } ?? "无记录")")
                    .font(EyeStyle.body).monospacedDigit()
            }
        }
    }
    private var weekdays: some View {
        ScrollView(.horizontal,showsIndicators: false) {
            HStack(spacing: 2) {
                ForEach(0..<7) { i in
                    Button { selected = i } label: {
                        VStack(spacing: 2) {
                            Text(["一","二","三","四","五","六","日"][i])
                            Text("\(calendar.component(.day,from: dates[i]))").monospacedDigit()
                        }.font(EyeStyle.caption).frame(minWidth: 44,minHeight: 48)
                            .foregroundStyle(i == selected ? EyeStyle.onPrimary : EyeStyle.text)
                            .background(i == selected ? EyeStyle.primary : EyeStyle.surfaceVariant,in: RoundedRectangle(cornerRadius: EyeStyle.radiusSmall))
                    }.buttonStyle(.plain).disabled(calendar.startOfDay(for: dates[i])>calendar.startOfDay(for: today))
                        .accessibilityLabel("\(key(dates[i]))，星期\(["一","二","三","四","五","六","日"][i])")
                        .accessibilityAddTraits(i == selected ? .isSelected : [])
                }
            }
        }
    }
    private func line(_ values: [Double?]) -> some View {
        Canvas { context,size in
            let maximum = max(1,values.compactMap { $0 }.max() ?? 1)*1.1
            func point(_ i: Int,_ value: Double) -> CGPoint {
                CGPoint(x: 6+(size.width-12)*CGFloat(i)/6,y: size.height-6-(size.height-12)*CGFloat(value/maximum))
            }
            for i in 0..<3 {
                let y = 6+(size.height-12)*CGFloat(i)/2
                var grid = Path(); grid.move(to: CGPoint(x: 0,y: y)); grid.addLine(to: CGPoint(x: size.width,y: y))
                context.stroke(grid,with: .color(EyeStyle.borderSubtle),lineWidth: 1)
            }
            var path = Path(); var connected = false
            for (i,value) in values.enumerated() {
                guard let value else { connected = false; continue }
                let p = point(i,value),r: CGFloat = selected == i ? 5 : 3
                if connected { path.addLine(to: p) } else { path.move(to: p) }; connected = true
                context.fill(Path(ellipseIn: CGRect(x: p.x-r,y: p.y-r,width: r*2,height: r*2)),with: .color(EyeStyle.ice))
            }
            context.stroke(path,with: .color(EyeStyle.ice),lineWidth: 2)
        }
    }
}
