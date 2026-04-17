import SwiftUI
import UIKit

struct SessionShareSheet: UIViewControllerRepresentable {
    let session: SavedSessionSummary

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let url = URL(fileURLWithPath: session.absolutePath)
        let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        controller.setValue(session.fileName, forKey: "subject")
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

final class SessionShareState: ObservableObject {
    @Published var pendingSession: SavedSessionSummary? = nil
}
