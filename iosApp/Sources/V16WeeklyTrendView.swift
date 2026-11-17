import SwiftUI

/// 周、星期与三条曲线共用选择状态；缺测保留断点，不补造完整数据。
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
    private struct Metric {
        let title: String; let unit: String; let values: [Double?]; let color: Color
    }
    private var metrics: [Metric] {
        [Metric(title: "平均每日用屏",unit: "分钟",values: days.map { $0?.screenMinutes.map(Double.init) },color: EyeStyle.screen),
         Metric(title: "平均连续使用时长",unit: "分钟",values: days.map { $0?.longestSessionMinutes.map(Double.init) },color: EyeStyle.sun),
         Metric(title: "平均眨眼频率",unit: "次/分",values: days.map { $0?.blinksPerMinute },color: EyeStyle.ice)]
    }
    var body: some View {
        SegmentedPicker(options: [(0,"本周"),(1,"上周")],selected: week) { week = $0 }
        Text("\(key(dates[0])) — \(key(dates[6]))").font(EyeStyle.subtitle)
        weekdays
        ForEach(Array(metrics.enumerated()),id: \.offset) { _,metric in
            WarmCard {
                Text(metric.title).font(EyeStyle.subtitle)
                let values = metric.values.compactMap { $0 }
                Text(values.isEmpty ? "—" : "\(Int((values.reduce(0,+)/Double(values.count)).rounded())) \(metric.unit)")
                    .font(EyeStyle.metricValue).monospacedDigit()
                if values.isEmpty { Text("暂无记录").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary) }
                line(metric).frame(height: 112).accessibilityHidden(true)
                weekdays
                Text("\(key(dates[selected])) · \(metric.values[selected].map { "\(Int($0.rounded())) \(metric.unit)" } ?? "无记录")")
                    .font(EyeStyle.body).monospacedDigit()
            }
        }
        Text(metrics.allSatisfy { $0.values[selected] == nil } ? "这一天暂无可用记录。" : "当天原始读数见上方；平均值仅包含有记录的日期。")
            .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
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
    private func line(_ metric: Metric) -> some View {
        Canvas { context,size in
            let maximum = max(1,metric.values.compactMap { $0 }.max() ?? 1)*1.1
            func point(_ i: Int,_ value: Double) -> CGPoint {
                CGPoint(x: 6+(size.width-12)*CGFloat(i)/6,y: size.height-6-(size.height-12)*CGFloat(value/maximum))
            }
            for i in 0..<3 {
                let y = 6+(size.height-12)*CGFloat(i)/2
                var grid = Path(); grid.move(to: CGPoint(x: 0,y: y)); grid.addLine(to: CGPoint(x: size.width,y: y))
                context.stroke(grid,with: .color(EyeStyle.borderSubtle),lineWidth: 1)
            }
            var path = Path(); var connected = false
            for (i,value) in metric.values.enumerated() {
                guard let value else { connected = false; continue }
                let p = point(i,value),r: CGFloat = selected == i ? 5 : 3
                if connected { path.addLine(to: p) } else { path.move(to: p) }; connected = true
                context.fill(Path(ellipseIn: CGRect(x: p.x-r,y: p.y-r,width: r*2,height: r*2)),with: .color(metric.color))
            }
            context.stroke(path,with: .color(metric.color),lineWidth: 2)
        }
    }
}
