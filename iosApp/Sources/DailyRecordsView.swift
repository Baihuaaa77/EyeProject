import SwiftUI

struct DailyRecordsView: View {
    let report: CareReport
    @State private var offset = 0
    @State private var selected = 0
    @State private var detail: Int?
    private let metrics = [
        ("screen", "今日用屏"), ("longest", "最长连续使用"), ("light", "环境光"),
        ("brightness", "屏幕亮度"), ("distance", "用眼距离"), ("night", "夜间使用")
    ]

    private var date: Date { Calendar.current.date(byAdding: .day, value: offset, to: Date()) ?? Date() }
    private var day: CareReport.Day? {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return report.historyDays.first { $0.id == formatter.string(from: date) }
    }
    private var dateLabel: String {
        let parts = Calendar(identifier: .gregorian).dateComponents([.month, .day], from: date)
        return "\(parts.month ?? 0)月\(parts.day ?? 0)日"
    }

    var body: some View {
        SubPage(title: "用眼全记录") {
            HStack {
                Button("前一天") { offset -= 1 }.buttonStyle(EyeButtonStyle(role: .secondary)).disabled(offset <= -13)
                Button("后一天") { offset += 1 }.buttonStyle(EyeButtonStyle(role: .secondary)).disabled(offset >= 0)
            }
            WarmCard {
                Text(dateLabel + "用眼负荷").font(EyeStyle.subtitle)
                EyeStyle.reading(day?.score.map { "\($0) / 100" } ?? "—")
                if offset == 0, let tip = report.tips.first { Text(tip.text).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary) }
            }
            GeometryReader { geometry in
                let width = geometry.size.width * 2 / 3
                HStack(alignment: .top, spacing: 10) {
                    tile(selected).frame(width: width)
                    tile((selected + 1) % metrics.count).frame(width: width)
                }
                .frame(width: geometry.size.width, alignment: .leading)
                .clipped()
                .contentShape(Rectangle())
                .simultaneousGesture(DragGesture(minimumDistance: 25).onEnded { gesture in
                    let delta = gesture.translation
                    guard abs(delta.width) > abs(delta.height) else { return }
                    move(delta.width < 0 ? 1 : -1)
                })
            }.frame(height: 116)
            HStack {
                Button("上一项") { move(-1) }.buttonStyle(EyeButtonStyle(role: .secondary))
                Button("下一项") { move(1) }.buttonStyle(EyeButtonStyle(role: .secondary))
            }
            Text("\(selected + 1) / \(metrics.count)").font(EyeStyle.caption).foregroundStyle(EyeStyle.secondary)
        }
        .navigationDestination(isPresented: Binding(get: { detail != nil }, set: { if !$0 { detail = nil } })) {
            if let detail {
                SubPage(title: metrics[detail].1) {
                    WarmCard {
                        Text(dateLabel).font(EyeStyle.bodySecondary)
                        EyeStyle.reading(value(detail), numberFont: EyeStyle.display)
                        if offset == 0, let note = signal(detail)?.note, !note.isEmpty {
                            Text(note).font(EyeStyle.body).foregroundStyle(EyeStyle.secondary)
                        }
                    }
                }
            }
        }
    }

    private func tile(_ index: Int) -> some View {
        Button { selected = index; detail = index } label: {
            WarmCard {
                Text(metrics[index].1).font(EyeStyle.navigationTitle).foregroundStyle(EyeStyle.text)
                EyeStyle.reading(value(index))
            }
        }.buttonStyle(.plain)
    }

    private func move(_ step: Int) {
        selected = (metrics.count + selected + step) % metrics.count
    }

    private func signal(_ index: Int) -> CareReport.Signal? { report.signals.first { $0.id == metrics[index].0 } }

    private func value(_ index: Int) -> String {
        if offset == 0 { return signal(index)?.value ?? "—" }
        let minutes = switch metrics[index].0 {
        case "screen": day?.screenMinutes
        case "longest": day?.longestSessionMinutes
        case "night": day?.nightMinutes
        default: nil as Int?
        }
        guard let minutes else { return "—" }
        return "\(minutes / 60)小时\(minutes % 60)分"
    }
}
