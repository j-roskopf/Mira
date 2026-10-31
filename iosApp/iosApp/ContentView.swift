import SwiftUI
import ComposeApp

struct ContentView: View {
    @State private var pendingImportUrl: String?
    @State private var pendingSharedToken: String?

    var body: some View {
        ComposeView(
            pendingImportUrl: $pendingImportUrl,
            pendingSharedToken: $pendingSharedToken
        )
            .ignoresSafeArea()
            .onOpenURL { url in
                if let token = Self.extractSharedToken(from: url) {
                    pendingSharedToken = token
                } else if let importUrl = Self.extractImportUrl(from: url) {
                    pendingImportUrl = importUrl
                }
            }
    }

    private static func extractImportUrl(from url: URL) -> String? {
        if url.scheme == "mira", url.host == "import" {
            return URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?
                .first(where: { $0.name == "url" })?
                .value
        }
        if url.scheme == "http" || url.scheme == "https" {
            return url.absoluteString
        }
        return nil
    }

    private static func extractSharedToken(from url: URL) -> String? {
        guard (url.scheme == "http" || url.scheme == "https"),
              url.host == "mira.joetr.com" else { return nil }
        let components = url.pathComponents
        guard components.count >= 3, components[1] == "share" else { return nil }
        return components[2]
    }
}

struct ComposeView: UIViewControllerRepresentable {
    @Binding var pendingImportUrl: String?
    @Binding var pendingSharedToken: String?

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        if let url = pendingImportUrl {
            ShareIntentIosKt.setPendingShareUrl(url: url)
            pendingImportUrl = nil
        }
        if let token = pendingSharedToken {
            ShareIntentIosKt.setPendingSharedRecipeToken(token: token)
            pendingSharedToken = nil
        }
    }
}
