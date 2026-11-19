import SwiftUI
import WebKit

enum OfflineSession: String, Identifiable {
    case garden, firefly, color, post, rest, booking
    var id: String { rawValue }

    init?(test: String) {
        switch test {
        case "DRY_EYE": self = .garden
        case "STRABISMUS": self = .firefly
        case "COLOR_VISION": self = .color
        case "PRESBYOPIA": self = .post
        default: return nil
        }
    }

    var title: String {
        switch self {
        case .garden: "干眼自测 · 试玩"
        case .firefly: "眼位自测 · 试玩"
        case .color: "色觉自测 · 试玩"
        case .post: "老视自测 · 试玩"
        case .rest: "转转眼睛"
        case .booking: "智能挂号 · 演示"
        }
    }
}

struct OfflineSessionCover: View {
    let session: OfflineSession
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            OfflineSessionWebView(session: session, close: { dismiss() })
                .navigationTitle(session.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button("返回") { dismiss() }
                    }
                }
        }.tint(EyeStyle.primary)
    }
}

/// 离线会话仅渲染随包页面，无账户、相机、记录或原生对象接口。
private struct OfflineSessionWebView: UIViewRepresentable {
    let session: OfflineSession
    let close: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(close: close) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: config)
        view.navigationDelegate = context.coordinator
        view.isOpaque = false
        view.backgroundColor = UIColor(EyeStyle.background)
        if let entry = Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "EyeOffline"),
           var url = URLComponents(url: entry, resolvingAgainstBaseURL: false) {
            url.fragment = "page=\(session.rawValue)"
            if let localURL = url.url {
                context.coordinator.entry = entry
                view.loadFileURL(localURL, allowingReadAccessTo: entry.deletingLastPathComponent())
            }
        }
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate {
        let close: () -> Void
        var entry: URL?

        init(close: @escaping () -> Void) { self.close = close }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url else { decisionHandler(.cancel); return }
            if url.absoluteString == "eye-session://close" && action.targetFrame?.isMainFrame == true {
                decisionHandler(.cancel)
                close()
            } else {
                decisionHandler(url.isFileURL && url.path == entry?.path ? .allow : .cancel)
            }
        }
    }
}
