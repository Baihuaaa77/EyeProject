import SwiftUI
import WebKit
import UIKit

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
        case .booking: "智能挂号"
        }
    }
}

struct OfflineSessionCover: View {
    let session: OfflineSession
    var prefillDryEye = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            OfflineSessionWebView(session: session, prefillDryEye: prefillDryEye, close: { dismiss() })
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

/// 随包会话保持离线。挂号仅传显示名，通过明确的按钮操作复制草稿或打开固定官方地址。
private struct OfflineSessionWebView: UIViewRepresentable {
    let session: OfflineSession
    let prefillDryEye: Bool
    let close: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(close: close, booking: session == .booking) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: config)
        view.navigationDelegate = context.coordinator
        view.isOpaque = false
        view.backgroundColor = UIColor(EyeStyle.background)
        if let entry = Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "EyeOffline"),
           var url = URLComponents(url: entry, resolvingAgainstBaseURL: false) {
            url.fragment = "page=\(session.rawValue)" + (session == .booking && prefillDryEye ? "&chief=surface" : "")
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
        let booking: Bool
        var entry: URL?

        init(close: @escaping () -> Void, booking: Bool) { self.close = close; self.booking = booking }

        private static let officialBookingLinks = [
            "appointment": "https://www.aierchina.com/yuyue/",
            "changsha-appointment": "https://yuyuehz.aierchina.com/yuyue/pc?cdes=yuyue&cid=1&hid=1&pid=346",
            "directory": "https://www.aierchina.com/zhaoyiyuan/",
            "doctor-lin": "https://www.aierchina.com/index.php?c=show&id=144",
            "doctor-zeng": "https://www.aierchina.com/mobile/jmjybzj/93.html",
        ]

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard booking, webView.url?.path == entry?.path else { return }
            let names = SubjectStore.shared.subjects.prefix(30).map { String($0.nickname.prefix(40)) }
            guard let data = try? JSONSerialization.data(withJSONObject: names),
                  let json = String(data: data, encoding: .utf8) else { return }
            webView.evaluateJavaScript("window.bookingPage?.setPatients(\(json))", completionHandler: nil)
        }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url else { decisionHandler(.cancel); return }
            if url.isFileURL && url.path == entry?.path {
                decisionHandler(.allow)
                return
            }
            decisionHandler(.cancel)
            guard url.scheme == "eye-session", action.targetFrame?.isMainFrame == true else { return }
            if url.absoluteString == "eye-session://close" {
                close()
            } else if booking, webView.url?.path == entry?.path, let command = url.host {
                handleBookingAction(command, in: webView)
            }
        }

        private func handleBookingAction(_ command: String, in webView: WKWebView) {
            if command == "copy-draft" {
                webView.evaluateJavaScript("window.bookingPage?.copyText()") { [weak webView] result, _ in
                    guard let webView else { return }
                    guard let draft = result as? String, !draft.isEmpty, draft.count <= 2_500 else {
                        webView.evaluateJavaScript("window.bookingPage?.status('暂时无法复制草稿。')", completionHandler: nil)
                        return
                    }
                    UIPasteboard.general.setItems([["public.utf8-plain-text": draft]], options: [.localOnly: true])
                    webView.evaluateJavaScript("window.bookingPage?.status('已复制预问诊草稿。')", completionHandler: nil)
                }
            } else if let link = Self.officialBookingLinks[command], let url = URL(string: link) {
                UIApplication.shared.open(url) { [weak webView] success in
                    guard !success else { return }
                    webView?.evaluateJavaScript("window.bookingPage?.status('无法打开爱尔官网，请在浏览器访问 aierchina.com。')",
                        completionHandler: nil)
                }
            }
        }
    }
}
