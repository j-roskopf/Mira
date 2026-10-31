import UIKit
import UniformTypeIdentifiers

class ShareViewController: UIViewController {
    private let appGroupId = "group.com.joetr.mira"
    private let pendingUrlKey = "pendingShareUrl"

    override func viewDidLoad() {
        super.viewDidLoad()
        handleShare()
    }

    private func handleShare() {
        guard let item = extensionContext?.inputItems.first as? NSExtensionItem else {
            finish()
            return
        }
        let providers = item.attachments ?? []
        for provider in providers {
            if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { item, _ in
                    if let url = item as? URL {
                        self.save(url: url.absoluteString)
                    } else if let urlString = item as? String {
                        self.save(url: urlString)
                    }
                    self.finish()
                }
                return
            }
            if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { item, _ in
                    if let text = item as? String, text.hasPrefix("http") {
                        self.save(url: text.trimmingCharacters(in: .whitespacesAndNewlines))
                    }
                    self.finish()
                }
                return
            }
        }
        finish()
    }

    private func save(url: String) {
        UserDefaults(suiteName: appGroupId)?.set(url, forKey: pendingUrlKey)
    }

    private func finish() {
        extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
    }
}
