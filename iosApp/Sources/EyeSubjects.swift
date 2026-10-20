import SwiftUI
import EyeScreening

/// 被测者（本人与家庭成员）：原先每项测试开头的问卷合并为一份资料，在「我的」维护，测试时只选「这次测谁」。
/// 题目、必需项与年龄换算全部来自共用 SubjectProfiles；本机以 JSON 文件保存（iOS 尚未接入云端同步）。
struct SubjectProfileItem: Identifiable, Equatable {
    var id: String
    var relation: String
    var nickname: String
    var fields: [String: String]
    var updatedAt: Double

    var displayName: String { nickname.isEmpty ? SubjectProfiles.shared.relationLabel(code: relation) : nickname }
    var json: [String: Any] { ["id": id, "relation": relation, "nickname": nickname, "fields": fields, "updatedAt": updatedAt] }
    var jsonText: String {
        (try? JSONSerialization.data(withJSONObject: json)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }
}

final class SubjectStore: ObservableObject {
    static let shared = SubjectStore()
    @Published private(set) var subjects: [SubjectProfileItem] = []
    private let file = EyeRuntime.recordsDirectory.deletingLastPathComponent().appendingPathComponent("subjects.json")

    private init() {
        let data = (try? Data(contentsOf: file)).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [[String: Any]] } ?? []
        subjects = data.compactMap { o in
            guard let id = o["id"] as? String else { return nil }
            return SubjectProfileItem(id: id, relation: o["relation"] as? String ?? "OTHER", nickname: o["nickname"] as? String ?? "",
                                      fields: o["fields"] as? [String: String] ?? [:], updatedAt: (o["updatedAt"] as? NSNumber)?.doubleValue ?? 0)
        }
        // 首次使用：建立空白的「本人」
        if !subjects.contains(where: { $0.relation == "SELF" }) {
            subjects.insert(SubjectProfileItem(id: UUID().uuidString.lowercased(), relation: "SELF", nickname: "我", fields: [:], updatedAt: 0), at: 0)
        }
    }

    func save(_ item: SubjectProfileItem) {
        var next = item
        next.updatedAt = Date().timeIntervalSince1970 * 1000
        if next.nickname.trimmingCharacters(in: .whitespaces).isEmpty {
            next.nickname = next.relation == "SELF" ? "我" : SubjectProfiles.shared.relationLabel(code: next.relation)
        }
        if let index = subjects.firstIndex(where: { $0.id == item.id }) { subjects[index] = next } else { subjects.append(next) }
        persist()
    }

    func delete(_ id: String) {
        subjects.removeAll { $0.id == id && $0.relation != "SELF" }
        persist()
    }

    private func persist() {
        let sorted = subjects.sorted { ($0.relation == "SELF" ? 0 : 1) < ($1.relation == "SELF" ? 0 : 1) }
        subjects = sorted
        if let data = try? JSONSerialization.data(withJSONObject: sorted.map(\.json)) { try? data.write(to: file, options: .atomic) }
    }
}

/// 一行摘要：关系 · 年龄 · 已填几项。
private func subjectSummary(_ s: SubjectProfileItem) -> String {
    var parts = [SubjectProfiles.shared.relationLabel(code: s.relation)]
    if let year = Int(s.fields["出生年份"] ?? "") { parts.append("\(Calendar.current.component(.year, from: Date()) - year) 岁") }
    let filled = s.fields.values.filter { !$0.isEmpty }.count
    parts.append(filled == 0 ? "资料未填写" : "已填 \(filled) 项")
    return parts.joined(separator: " · ")
}

/// 「我的 → 家庭成员与资料」：与安卓同一排版。
struct SubjectsSection: View {
    @ObservedObject var store = SubjectStore.shared
    @State private var editing: SubjectProfileItem?
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("家庭成员与资料").font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
            Text("测试前选择这次测谁，资料自动带入，不用每次填写问卷。").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            ForEach(store.subjects) { s in
                Button { editing = s } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(s.displayName).font(EyeStyle.body).foregroundStyle(EyeStyle.text)
                            Text(subjectSummary(s)).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(EyeStyle.secondary)
                    }
                }.buttonStyle(EyeCardButtonStyle())
            }
            Button("添加家庭成员") { editing = SubjectStore.newMember() }.buttonStyle(EyeButtonStyle(role: .secondary))
        }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radius))
            .sheet(item: $editing) { SubjectEditView(item: $0) }
    }
}

extension SubjectStore {
    static func newMember() -> SubjectProfileItem {
        SubjectProfileItem(id: UUID().uuidString.lowercased(), relation: "CHILD", nickname: "", fields: [:], updatedAt: 0)
    }
}

/// 资料编辑：题目分组与追问（如选了白内障手术才出现人工晶体类型）由共用 editorJson 给出。
struct SubjectEditView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: SubjectProfileItem
    @State private var confirmDelete = false
    @State private var error: String?
    private let isNew: Bool

    init(item: SubjectProfileItem) {
        _draft = State(initialValue: item)
        isNew = !SubjectStore.shared.subjects.contains { $0.id == item.id }
    }

    private var sections: [[String: Any]] {
        let fieldsJson = (try? JSONSerialization.data(withJSONObject: draft.fields)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return (try? JSONSerialization.jsonObject(with: Data(SubjectProfiles.shared.editorJson(fieldsJson: fieldsJson).utf8))) as? [[String: Any]] ?? []
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("测试时只需选择成员，不用每次填写问卷。都可以先不填，测试会提示缺少哪些必需项。")
                        .font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                    card("成员") {
                        TextField(draft.relation == "SELF" ? "我" : "称呼，如：小明、妈妈", text: $draft.nickname).textFieldStyle(.roundedBorder)
                        if draft.relation != "SELF" {
                            Text("与你的关系").font(EyeStyle.subtitle)
                            // 关系代码与共用 SubjectProfiles.RELATIONS 一致（「本人」不可选）
                            ForEach(["CHILD", "PARENT", "SPOUSE", "OTHER"], id: \.self) { code in
                                choiceRow(SubjectProfiles.shared.relationLabel(code: code), hint: nil, selected: draft.relation == code) { draft.relation = code }
                            }
                        }
                    }
                    ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
                        card(section["title"] as? String ?? "") {
                            ForEach(Array((section["questions"] as? [[String: Any]] ?? []).enumerated()), id: \.offset) { _, q in question(q) }
                        }
                    }
                    if let error { Text(error).foregroundStyle(EyeStyle.danger) }
                    if confirmDelete { Text("将删除该成员的资料；已保存的测试记录保留在本机。再点一次「确认删除」继续。").font(EyeStyle.bodySecondary) }
                    HStack(spacing: 8) {
                        if draft.relation != "SELF" && !isNew {
                            Button(confirmDelete ? "确认删除" : "删除成员") {
                                if confirmDelete { SubjectStore.shared.delete(draft.id); dismiss() } else { confirmDelete = true }
                            }.buttonStyle(EyeButtonStyle(role: .danger))
                        }
                        Button("保存") { save() }.buttonStyle(EyeButtonStyle())
                    }
                }.padding(20)
            }.background(EyeStyle.background)
                .navigationTitle(isNew ? "添加家庭成员" : "\(draft.displayName)的资料").navigationBarTitleDisplayMode(.inline)
                .toolbar { Button("取消") { dismiss() } }
        }
    }

    private func save() {
        // 填了就必须有效；隐藏的追问（如取消手术后的手术类型）不保存
        let fieldsJson = (try? JSONSerialization.data(withJSONObject: draft.fields)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        if let message = SubjectProfiles.shared.validationErrorJson(fieldsJson: fieldsJson) { error = message; return }
        let visible = sections.flatMap { $0["questions"] as? [[String: Any]] ?? [] }
        if let bad = visible.first(where: { $0["valid"] as? Bool == false }) { error = "「\(bad["title"] as? String ?? "")」填写有误"; return }
        let keys = Set(visible.compactMap { $0["key"] as? String })
        draft.fields = draft.fields.filter { keys.contains($0.key) && !$0.value.isEmpty }
        SubjectStore.shared.save(draft)
        dismiss()
    }

    @ViewBuilder private func question(_ q: [String: Any]) -> some View {
        let key = q["key"] as? String ?? ""
        let value = draft.fields[key] ?? ""
        VStack(alignment: .leading, spacing: 8) {
            Text((q["title"] as? String ?? key) + (q["multiple"] as? Bool == true ? "（可多选）" : "")).font(EyeStyle.subtitle)
            if let options = q["options"] as? [String] {
                let hints = q["hints"] as? [String: String] ?? [:]
                ForEach(options, id: \.self) { option in
                    let selected = ("、" + value + "、").contains("、" + option + "、")
                    choiceRow(option, hint: hints[option], selected: selected) {
                        if q["multiple"] as? Bool == true {
                            let exclusive = Set(q["exclusive"] as? [String] ?? [])
                            var chosen = options.filter { ("、" + value + "、").contains("、" + $0 + "、") }
                            if selected { chosen.removeAll { $0 == option } }
                            else if exclusive.contains(option) { chosen = [option] }
                            else { chosen.removeAll { exclusive.contains($0) }; chosen.append(option) }
                            draft.fields[key] = chosen.joined(separator: "、")
                        } else { draft.fields[key] = selected ? "" : option }
                    }
                }
            } else {
                let inputKind = q["inputKind"] as? String ?? "TEXT"
                let limit = q["maxLength"] as? Int ?? 200
                TextField(key == "出生年份" ? "如：1980" : (q["placeholder"] as? String ?? "请输入"), text: Binding(get: { draft.fields[key] ?? "" },
                    set: { text in
                        let filtered = text.filter { char in
                            switch inputKind {
                            case "INTEGER": return char.isNumber
                            case "DECIMAL": return char.isNumber || "+-.".contains(char)
                            case "DATE": return char.isNumber || char == "-"
                            default: return true
                            }
                        }
                        draft.fields[key] = String(filtered.prefix(limit))
                    }))
                    // 度数要输入正负号、日期要输入「-」：用带符号的数字键盘
                    .keyboardType(inputKind == "INTEGER" ? .numberPad : inputKind == "TEXT" ? .default : .numbersAndPunctuation)
                    .textFieldStyle(.roundedBorder)
            }
        }
    }

    private func choiceRow(_ label: String, hint: String?, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                    if let hint { Text(hint).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary) }
                }
                Spacer()
                if selected { Image(systemName: "checkmark").foregroundStyle(EyeStyle.primary) }
            }
        }.buttonStyle(EyeCardButtonStyle(selected: selected))
    }

    private func card<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(EyeStyle.subtitle).foregroundStyle(EyeStyle.text)
            content()
        }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(EyeStyle.panel, in: RoundedRectangle(cornerRadius: EyeStyle.radius))
    }
}

/// 测试开始页「这次测谁？」：替代原先的逐题问卷；缺必需项的成员点开即去补充。与安卓一致。
struct SubjectPickerView: View {
    @ObservedObject var runtime: EyeRuntime
    @ObservedObject var store = SubjectStore.shared
    @State private var editing: SubjectProfileItem?
    @State private var chosenId: String?
    @State private var mode = "标准模式"

    var body: some View {
        VStack(alignment: .leading, spacing: EyeStyle.spacing) {
            Text(runtime.kind == "STRABISMUS" ? "这次给谁测？" : "这次测谁？").font(EyeStyle.title)
            Text("资料在「我的 → 家庭成员与资料」中填写和修改，测试时自动带入。").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            ForEach(store.subjects) { s in
                let missing = runtime.missing(s)
                Button {
                    if missing.isEmpty { chosenId = s.id; runtime.useSubject(s) } else { editing = s }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(s.displayName).font(EyeStyle.body)
                        Text(subjectSummary(s)).font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
                        if !missing.isEmpty {
                            Text("需补充：\(missing.joined(separator: "、"))（点此去填写）").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.warning)
                        }
                    }
                }.buttonStyle(EyeCardButtonStyle(selected: chosenId == s.id))
            }
            Button("添加家庭成员") { editing = SubjectStore.newMember() }.buttonStyle(EyeButtonStyle(role: .secondary))
            if runtime.kind == "PRESBYOPIA" && chosenId != nil {
                Text("这次测试戴哪副眼镜？").font(EyeStyle.subtitle)
                Picker("测试模式", selection: $mode) {
                    Text("戴看远的眼镜，或不戴眼镜（推荐）").tag("标准模式")
                    Text("戴平时看近用的眼镜").tag("日常模式")
                }.pickerStyle(.inline)
                Button("开始") { runtime.proceed(mode: mode) }.buttonStyle(EyeButtonStyle())
            }
            VStack(spacing: 2) {
                Button("暂不选择，直接测试") { runtime.act("skipProfile") }
                    .font(EyeStyle.body).foregroundStyle(EyeStyle.primary).frame(minHeight: 44)
                Text("不选成员时，依赖年龄和症状的判断会注明无法评估。").font(EyeStyle.bodySecondary).foregroundStyle(EyeStyle.secondary)
            }.frame(maxWidth: .infinity)
        }.sheet(item: $editing) { SubjectEditView(item: $0) }
    }
}
